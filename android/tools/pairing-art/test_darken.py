"""Process-contract tests; no image libraries, ffmpeg or generated artwork needed."""
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
from types import ModuleType
import unittest
from unittest.mock import Mock, patch


FRAME = bytes(1050 * 354 * 3)


class FragmentedReader(io.BytesIO):
    def __init__(self, data):
        super().__init__(data)
        self.limits = iter((7, 13))

    def read(self, size):
        return super().read(min(size, next(self.limits, size)))


class EncoderInput:
    def __init__(self, fail=False):
        self.closed, self.fail, self.frames = False, fail, []

    def write(self, data):
        if self.fail: raise BrokenPipeError("encoder stopped")
        self.frames.append(data)
        return len(data)

    def close(self):
        self.closed = True


class Process:
    def __init__(self, data=b"", code=0, fail_write=False):
        self.args = ["ffmpeg", "fixture"]
        self.stdout = FragmentedReader(data)
        self.stdin = EncoderInput(fail_write)
        self.code, self.returncode, self.killed, self.waits = code, None, False, 0

    def wait(self):
        self.waits += 1
        self.returncode = -9 if self.killed else self.code
        return self.returncode

    def poll(self):
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9


class DarkenVideoTest(unittest.TestCase):
    def setUp(self):
        libraries = {name: ModuleType(name) for name in ("numpy", "scipy", "PIL")}
        libraries["numpy"].array = Mock()
        libraries["numpy"].float32 = object()
        libraries["numpy"].uint8 = object()
        libraries["numpy"].frombuffer = Mock()
        libraries["scipy"].ndimage = Mock()
        libraries["PIL"].Image = Mock()
        spec = importlib.util.spec_from_file_location("darken_generator", Path(__file__).with_name("darken.py"))
        self.module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, libraries), patch.object(sys, "argv", ["darken.py"]), \
                patch.object(subprocess, "Popen") as started:
            spec.loader.exec_module(self.module)
            started.assert_not_called()
        self.module.darken = Mock()
        self.module.darken.return_value.tobytes.return_value = b"processed frame"

    def assert_reaped(self, decoder, encoder=None):
        self.assertTrue(decoder.stdout.closed)
        self.assertGreater(decoder.waits, 0)
        if encoder is not None:
            self.assertTrue(encoder.stdin.closed)
            self.assertGreater(encoder.waits, 0)

    def test_success_keeps_frame_shape_and_render_arguments_after_fragmented_reads(self):
        decoder, encoder = Process(FRAME * 2), Process()
        with patch.object(subprocess, "Popen", side_effect=[decoder, encoder]) as started:
            self.assertEqual(2, self.module.darken_video("input.mp4", "output.mp4"))
        self.assertEqual([b"processed frame"] * 2, encoder.stdin.frames)
        self.module.np.frombuffer.return_value.reshape.assert_called_with(354, 1050, 3)
        arguments = started.call_args_list[1].args[0]
        self.assertEqual("1050x354", arguments[arguments.index("-s") + 1])
        self.assertEqual("30", arguments[arguments.index("-r") + 1])
        self.assertEqual("20", arguments[arguments.index("-crf") + 1])
        self.assertEqual("slow", arguments[arguments.index("-preset") + 1])
        self.assertFalse(decoder.killed or encoder.killed)
        self.assert_reaped(decoder, encoder)

    def test_decoder_failure_is_not_a_successful_empty_video(self):
        decoder, encoder = Process(code=11), Process()
        with patch.object(subprocess, "Popen", side_effect=[decoder, encoder]):
            with self.assertRaises(subprocess.CalledProcessError) as error:
                self.module.darken_video("input.mp4", "output.mp4")
        self.assertEqual(11, error.exception.returncode)
        self.assertTrue(encoder.killed)
        self.module.darken.assert_not_called()
        self.assert_reaped(decoder, encoder)

    def test_encoder_failure_is_not_reported_as_success(self):
        decoder, encoder = Process(FRAME), Process(code=12)
        with patch.object(subprocess, "Popen", side_effect=[decoder, encoder]):
            with self.assertRaises(subprocess.CalledProcessError) as error:
                self.module.darken_video("input.mp4", "output.mp4")
        self.assertEqual(12, error.exception.returncode)
        self.assert_reaped(decoder, encoder)

    def test_empty_and_truncated_decodes_are_rejected_and_children_reaped(self):
        for data, message in ((b"", "no frames"), (FRAME[:-1], "incomplete RGB frame")):
            with self.subTest(message=message):
                decoder, encoder = Process(data), Process()
                with patch.object(subprocess, "Popen", side_effect=[decoder, encoder]):
                    with self.assertRaisesRegex(ValueError, message):
                        self.module.darken_video("input.mp4", "output.mp4")
                self.assertTrue(encoder.killed)
                self.assert_reaped(decoder, encoder)

    def test_encoder_startup_failure_stops_the_decoder(self):
        decoder = Process(FRAME)
        with patch.object(subprocess, "Popen", side_effect=[decoder, OSError("cannot start encoder")]):
            with self.assertRaisesRegex(OSError, "cannot start encoder"):
                self.module.darken_video("input.mp4", "output.mp4")
        self.assertTrue(decoder.killed)
        self.assert_reaped(decoder)

    def test_broken_pipe_and_processing_failure_stop_both_children(self):
        for failure in (BrokenPipeError, RuntimeError):
            with self.subTest(failure=failure):
                decoder, encoder = Process(FRAME), Process(fail_write=failure is BrokenPipeError)
                self.module.darken.side_effect = RuntimeError("matte failed") if failure is RuntimeError else None
                with patch.object(subprocess, "Popen", side_effect=[decoder, encoder]):
                    with self.assertRaises(failure):
                        self.module.darken_video("input.mp4", "output.mp4")
                self.assertTrue(decoder.killed and encoder.killed)
                self.assert_reaped(decoder, encoder)


if __name__ == "__main__":
    unittest.main()
