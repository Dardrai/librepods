package me.kavishdevar.librepods.eldprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Explicit instrumentation only. No recording, playback, Bluetooth, network or app integration. */
public final class EldProbe extends Instrumentation {
    private static final String MIME=MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int MAX_BUFFERS=256, MAX_BYTES=262144, MAX_BUFFER=8192;
    private long runDeadline;
    private File outputDirectory;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result=new Bundle();
        JSONObject report=new JSONObject();
        int code=Activity.RESULT_OK;
        try {
            runDeadline=SystemClock.elapsedRealtime()+60000;
            outputDirectory=new File(getTargetContext().getFilesDir(),"probe-"+System.currentTimeMillis());
            if(!outputDirectory.mkdirs()) throw new IllegalStateException("Cannot create probe output");
            report.put("schema",1).put("synthetic_only",true).put("runtime_probe",true)
                .put("sdk",Build.VERSION.SDK_INT).put("manufacturer",Build.MANUFACTURER)
                .put("model",Build.MODEL).put("requested_rate",48000).put("requested_channels",2)
                .put("requested_profile",39).put("requested_sbr_mode",0)
                .put("requested_bitrate",256000).put("requested_bitrate_mode","CBR")
                .put("input_pcm_frames",ProbeContract.INPUT_FRAMES)
                .put("input_chunk_frames",480).put("input_chunk_does_not_set_codec_frame_length",true)
                .put("input_pcm_sha256",hash(ProbeContract.syntheticPcm(ProbeContract.INPUT_FRAMES)))
                .put("required_asc","f8e65000");
            JSONArray inventory=new JSONArray(), attempts=new JSONArray();
            report.put("aac_inventory",inventory).put("encoder_attempts",attempts);
            int count=0;
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if(info.isAlias() || !supportsAac(info)) continue;
                JSONObject entry=new JSONObject().put("name",info.getName()).put("encoder",info.isEncoder())
                    .put("software_only",info.isSoftwareOnly()).put("hardware_accelerated",info.isHardwareAccelerated());
                inventory.put(entry);
                try {
                    MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(MIME);
                    JSONArray profiles=new JSONArray(); boolean eld=false;
                    for(MediaCodecInfo.CodecProfileLevel p:caps.profileLevels) {
                        profiles.put(p.profile); eld|=p.profile==MediaCodecInfo.CodecProfileLevel.AACObjectELD;
                    }
                    entry.put("advertised_profiles",profiles).put("advertises_eld",eld)
                        .put("advertises_requested_format",caps.isFormatSupported(encoderFormat()));
                } catch(Exception e) { entry.put("capability_error",error(e)); }
                if(info.isEncoder()) {
                    if(count>=8 || SystemClock.elapsedRealtime()>=runDeadline) {
                        entry.put("attempt_skipped","bounded run limit"); continue;
                    }
                    JSONObject attempt=new JSONObject().put("encoder",info.getName()); attempts.put(attempt);
                    attempt(info.getName(),count++,attempt);
                }
            }
            report.put("completed",true).put("no_encoder_attempt_is_not_proof_of_codec_absence",true);
        } catch(Exception e) {
            code=Activity.RESULT_CANCELED;
            try { report.put("fatal_error",error(e)); } catch(Exception ignored) { }
        } finally {
            try {
                if(outputDirectory!=null) {
                    File file=new File(outputDirectory,"report.json");
                    try(FileOutputStream out=new FileOutputStream(file)) {
                        out.write((report.toString(2)+"\n").getBytes("UTF-8"));
                    }
                    result.putString("report",file.getAbsolutePath());
                }
            } catch(Exception e) { result.putString("report_error",error(e)); code=Activity.RESULT_CANCELED; }
            result.putString("scope","synthetic MediaCodec only; no AirPods/recording/playback/network");
            finish(code,result);
        }
    }

    private void attempt(String name,int index,JSONObject result) throws Exception {
        Encoded encoded=null;
        try {
            result.put("stage","encode");
            encoded=encode(name,result);
            if(encoded.asc==null) throw new IllegalStateException("Encoder emitted no codec-specific data");
            result.put("actual_asc",ProbeContract.hex(encoded.asc));
            ProbeContract.Asc asc=null;
            try {
                asc=ProbeContract.parseAsc(encoded.asc);
                result.put("asc_object_type",asc.objectType).put("asc_sample_rate",asc.rate)
                    .put("asc_channel_config",asc.channelConfig).put("asc_core_frame_samples",asc.coreFrames)
                    .put("asc_core_frame_duration_us",asc.coreFrames*1000000.0/asc.rate)
                    .put("asc_sbr_present",asc.sbr).put("asc_resilience_flags",asc.resilienceFlags)
                    .put("asc_matches_required",asc.exactAppleAsc);
            } catch(IllegalArgumentException e) { result.put("asc_parse_error",error(e)); }
            saveEncoded(index,encoded);
            result.put("synthetic_au_file",index+".aus").put("stage","decode");
            JSONObject decoded=decode(encoded); result.put("decode",decoded);
            boolean candidate=asc!=null && asc.exactAppleAsc && encoded.maxSize<=321
                && encoded.units.size()>=16 && encoded.allPts10ms()
                && decoded.optBoolean("all_nonempty_buffers_480_frames")
                && decoded.optBoolean("nonzero_pcm") && decoded.optInt("sample_rate")==48000
                && decoded.optInt("channels")==2;
            result.put("matches_observed_core_contract",candidate).put("stage","completed")
                .put("does_not_prove_a2dp_transport_or_realtime_latency",true);
        } catch(Exception e) {
            result.put("error",error(e)).put("matches_observed_core_contract",false);
            if(encoded!=null && encoded.asc!=null) result.put("actual_asc",ProbeContract.hex(encoded.asc));
        }
    }

    private static MediaFormat encoderFormat() {
        MediaFormat format=MediaFormat.createAudioFormat(MIME,48000,2);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectELD);
        format.setInteger(MediaFormat.KEY_AAC_SBR_MODE,0);
        format.setInteger(MediaFormat.KEY_BIT_RATE,256000);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
        return format;
    }

    private Encoded encode(String name,JSONObject result) throws Exception {
        result.put("stage","create_encoder");
        MediaCodec codec=MediaCodec.createByCodecName(name); boolean started=false;
        Encoded encoded=new Encoded();
        try {
            result.put("stage","configure_encoder");
            codec.configure(encoderFormat(),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start(); started=true;
            result.put("stage","encode");
            long deadline=deadline();
            byte[] pcm=ProbeContract.syntheticPcm(ProbeContract.INPUT_FRAMES);
            int position=0; boolean inputEos=false, outputEos=false;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!outputEos) {
                checkDeadline(deadline);
                if(!inputEos) {
                    int slot=codec.dequeueInputBuffer(1000);
                    if(slot>=0) {
                        ByteBuffer input=codec.getInputBuffer(slot);
                        if(input==null) throw new IllegalStateException("Missing encoder input");
                        input.clear();
                        int bytes=Math.min(480*4,Math.min(pcm.length-position,input.remaining()/4*4));
                        if(position<pcm.length && bytes==0) throw new IllegalStateException("Encoder input too small");
                        if(bytes>0) input.put(pcm,position,bytes);
                        inputEos=position==pcm.length;
                        codec.queueInputBuffer(slot,0,bytes,ProbeContract.timeUs(position/4),
                            inputEos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                        position+=bytes;
                    }
                }
                int slot=codec.dequeueOutputBuffer(info,1000);
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format=codec.getOutputFormat();
                    result.put("encoder_output_format",format.toString());
                    if(format.containsKey("csd-0")) encoded.setAsc(copy(format.getByteBuffer("csd-0"),64));
                } else if(slot>=0) {
                    try {
                        if((info.flags&MediaCodec.BUFFER_FLAG_PARTIAL_FRAME)!=0)
                            throw new IllegalStateException("Partial encoded frame: not an AU boundary proof");
                        if(info.size>0) {
                            byte[] data=buffer(codec.getOutputBuffer(slot),info,MAX_BUFFER);
                            if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) encoded.setAsc(data);
                            else encoded.add(data,info.presentationTimeUs);
                        }
                        outputEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally { codec.releaseOutputBuffer(slot,false); }
                }
            }
            if(encoded.units.isEmpty()) throw new IllegalStateException("No encoded output");
            JSONArray rows=new JSONArray();
            for(Unit unit:encoded.units) rows.put(new JSONObject().put("size",unit.data.length)
                .put("pts_us",unit.pts).put("sha256",hash(unit.data)));
            result.put("encoded_buffers",rows).put("encoded_buffer_count",encoded.units.size())
                .put("encoded_total_bytes",encoded.total).put("encoded_max_buffer_bytes",encoded.maxSize)
                .put("all_encoded_pts_steps_10000us",encoded.allPts10ms());
            return encoded;
        } finally { close(codec,started); }
    }

    private JSONObject decode(Encoded encoded) throws Exception {
        ProbeContract.Asc header=ProbeContract.parseAsc(encoded.asc);
        MediaFormat format=MediaFormat.createAudioFormat(MIME,header.rate,header.channelConfig);
        format.setByteBuffer("csd-0",ByteBuffer.wrap(encoded.asc));
        format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
        String name=new MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format);
        if(name==null) throw new IllegalStateException("No decoder selected for actual ASC");
        MediaCodec codec=MediaCodec.createByCodecName(name); boolean started=false;
        JSONObject result=new JSONObject().put("decoder",name);
        try {
            codec.configure(format,null,null,0); codec.start(); started=true;
            long deadline=deadline();
            int position=0, channels=0, rate=0, pcmEncoding=0, buffers=0;
            long frames=0, firstPts=0, lastPts=0, nonzero=0;
            boolean inputEos=false, outputEos=false, all480=true;
            JSONObject histogram=new JSONObject();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!outputEos) {
                checkDeadline(deadline);
                if(!inputEos) {
                    int slot=codec.dequeueInputBuffer(1000);
                    if(slot>=0) {
                        ByteBuffer input=codec.getInputBuffer(slot);
                        if(input==null) throw new IllegalStateException("Missing decoder input");
                        input.clear();
                        inputEos=position==encoded.units.size();
                        Unit unit=inputEos ? null : encoded.units.get(position++);
                        if(unit!=null) {
                            if(unit.data.length>input.remaining()) throw new IllegalStateException("Decoder input too small");
                            input.put(unit.data);
                        }
                        long pts=unit==null ? encoded.units.get(encoded.units.size()-1).pts+20000 : unit.pts;
                        codec.queueInputBuffer(slot,0,unit==null?0:unit.data.length,pts,
                            inputEos?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                    }
                }
                int slot=codec.dequeueOutputBuffer(info,1000);
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output=codec.getOutputFormat();
                    int nextRate=output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int nextChannels=output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int nextEncoding=output.containsKey(MediaFormat.KEY_PCM_ENCODING)?
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(nextEncoding!=AudioFormat.ENCODING_PCM_16BIT || nextChannels<1 || nextChannels>8
                        || nextRate<8000 || nextRate>192000)
                        throw new IllegalStateException("Unsupported decoded PCM format");
                    if(channels!=0 && (channels!=nextChannels || rate!=nextRate || pcmEncoding!=nextEncoding))
                        throw new IllegalStateException("Decoded PCM changed format");
                    rate=nextRate; channels=nextChannels; pcmEncoding=nextEncoding;
                    result.put("output_format",output.toString());
                } else if(slot>=0) {
                    try {
                        if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                            if(channels==0 || info.size%(channels*2)!=0)
                                throw new IllegalStateException("Missing format or unaligned PCM");
                            byte[] data=buffer(codec.getOutputBuffer(slot),info,MAX_BYTES);
                            int count=data.length/(channels*2); frames+=count;
                            if(frames>ProbeContract.INPUT_FRAMES*4L || ++buffers>MAX_BUFFERS)
                                throw new IllegalStateException("Decoded PCM bound");
                            all480 &= count==480;
                            String key=Integer.toString(count); histogram.put(key,histogram.optInt(key)+1);
                            ByteBuffer pcm=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                            while(pcm.hasRemaining()) if(pcm.getShort()!=0) nonzero++;
                            digest.update(data);
                            if(buffers==1) firstPts=info.presentationTimeUs;
                            lastPts=info.presentationTimeUs;
                        }
                        outputEos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally { codec.releaseOutputBuffer(slot,false); }
                }
            }
            if(buffers==0) throw new IllegalStateException("No decoded PCM");
            return result.put("sample_rate",rate).put("channels",channels).put("pcm_encoding",pcmEncoding)
                .put("pcm_frames",frames).put("pcm_duration_us",frames*1000000.0/rate)
                .put("pcm_buffer_count",buffers).put("pcm_frames_per_buffer_histogram",histogram)
                .put("all_nonempty_buffers_480_frames",all480).put("nonzero_pcm",nonzero>0)
                .put("nonzero_sample_count",nonzero).put("pcm_sha256",ProbeContract.hex(digest.digest()))
                .put("first_output_pts_us",firstPts).put("last_output_pts_us",lastPts)
                .put("pcm_total_includes_codec_delay_and_eos_padding",true);
        } finally { close(codec,started); }
    }

    private void saveEncoded(int index,Encoded encoded) throws Exception {
        try(DataOutputStream out=new DataOutputStream(new FileOutputStream(new File(outputDirectory,index+".aus")))) {
            out.writeBytes("ELDPROBE1"); out.writeInt(encoded.asc.length); out.write(encoded.asc);
            out.writeInt(encoded.units.size());
            for(Unit unit:encoded.units) { out.writeInt(unit.data.length); out.writeLong(unit.pts); out.write(unit.data); }
        }
    }
    private long deadline() { return Math.min(runDeadline,SystemClock.elapsedRealtime()+10000); }
    private static void checkDeadline(long deadline) {
        if(SystemClock.elapsedRealtime()>=deadline) throw new IllegalStateException("Codec polling deadline");
    }
    private static boolean supportsAac(MediaCodecInfo info) {
        for(String type:info.getSupportedTypes()) if(type.equalsIgnoreCase(MIME)) return true;
        return false;
    }
    private static byte[] copy(ByteBuffer buffer,int max) {
        if(buffer==null || buffer.remaining()<1 || buffer.remaining()>max)
            throw new IllegalStateException("Codec data bound");
        ByteBuffer view=buffer.duplicate(); byte[] data=new byte[view.remaining()]; view.get(data); return data;
    }
    private static byte[] buffer(ByteBuffer buffer,MediaCodec.BufferInfo info,int max) {
        if(buffer==null || info.offset<0 || info.size<1 || info.size>max || info.offset>buffer.capacity()-info.size)
            throw new IllegalStateException("Codec buffer bound");
        ByteBuffer view=buffer.duplicate(); view.clear(); view.position(info.offset); view.limit(info.offset+info.size);
        return copy(view,max);
    }
    private static String hash(byte[] bytes) throws Exception {
        return ProbeContract.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String error(Exception e) {
        String text=e.getClass().getSimpleName()+": "+e.getMessage();
        return text.length()>500 ? text.substring(0,500):text;
    }
    private static void close(MediaCodec codec,boolean started) {
        try { if(started) codec.stop(); } catch(Exception ignored) { }
        finally { codec.release(); }
    }
    private static final class Unit {
        final byte[] data; final long pts;
        Unit(byte[] data,long pts) { this.data=data; this.pts=pts; }
    }
    private static final class Encoded {
        byte[] asc; final List<Unit> units=new ArrayList<>(); int total,maxSize;
        void setAsc(byte[] next) {
            if(next.length>64 || next.length==0 || (asc!=null && !Arrays.equals(asc,next)))
                throw new IllegalStateException("Invalid/changing ASC");
            asc=next.clone();
        }
        void add(byte[] data,long pts) {
            total+=data.length; maxSize=Math.max(maxSize,data.length);
            if(units.size()>=MAX_BUFFERS || total>MAX_BYTES) throw new IllegalStateException("Encoded data bound");
            units.add(new Unit(data,pts));
        }
        boolean allPts10ms() {
            if(units.size()<2) return false;
            for(int i=1;i<units.size();i++) if(units.get(i).pts-units.get(i-1).pts!=10000) return false;
            return true;
        }
    }
}
