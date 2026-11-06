"""Read the rewrite version without configuring Gradle or accessing signing secrets."""
import os
from pathlib import Path
import re
import subprocess
import sys


def app_version(properties, app_gradle):
    values = dict(line.strip().split("=", 1) for line in properties.splitlines()
                  if line.strip() and not line.lstrip().startswith(("#", "!")))
    major, minor, patch, build = (int(values[key]) for key in ("major", "minor", "patch", "build"))
    stage = values["stage"]
    if not (all(0 <= value <= 99 for value in (major, minor, patch)) and 1 <= build <= 99
            and stage in ("alpha", "beta", "rc", "stable")):
        raise ValueError("Invalid rewrite version.properties")
    suffix = re.search(r'^val appVersionName\s*=\s*\(rootProject.extra\["appVersionName"\] as String\)\s*\+\s*"(-pixel[0-9]+)"\s*$', app_gradle, re.M)
    if not suffix:
        raise ValueError("Cannot read the app's Pixel version suffix")
    prerelease = "" if stage == "stable" else f"-{stage}.{build}"
    return f"{major}.{minor}.{patch}{prerelease}{suffix[1]}"


def release_request(event, ref, requested, tag):
    tagged = event == "push" and ref.startswith("refs/tags/")
    publish = tagged or (event == "workflow_dispatch" and requested == "true")
    tag = ref.removeprefix("refs/tags/") if tagged else tag
    if publish and not re.fullmatch(r"(?:v|pixel-)[A-Za-z0-9][A-Za-z0-9._-]*", tag):
        raise ValueError("Publishing requires an explicit v* or pixel-* release tag")
    return publish, tag if publish else ""


def signing_candidate(repository, event, ref):
    return repository == "Ripthulhu/librepods" and (
        event == "workflow_dispatch" and ref == "refs/heads/main" or
        event == "push" and (ref == "refs/heads/main" or ref.startswith("refs/tags/")))


def trusted_signing_source(repository, event, ref, commit, main_commit, on_main):
    if not signing_candidate(repository, event, ref):
        return False
    return commit == main_commit if event == "workflow_dispatch" else on_main


def self_test():
    workflow = (Path(__file__).resolve().parents[1] / "workflows/ci-android.yml").read_text()
    assert "inputs.branch" not in workflow, "Source overrides can bypass the workflow ref's signing trust"
    assert "ref: ${{ github.ref }}" in workflow, "Checkout must use the same ref as the trusted workflow"
    properties = "major=2\nminor=0\npatch=0\nstage=alpha\nbuild=1\n"
    source = 'val appVersionName = (rootProject.extra["appVersionName"] as String) + "-pixel11"\n'
    assert app_version(properties, source) == "2.0.0-alpha.1-pixel11"
    assert app_version(properties.replace("alpha", "stable"), source) == "2.0.0-pixel11"
    for invalid_properties, invalid_source in ((properties.replace("build=1", "build=0"), source),
                                              (properties.replace("alpha", "unknown"), source),
                                              (properties, source.replace("-pixel11", "-pixel11\nbad=1"))):
        try:
            app_version(invalid_properties, invalid_source)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid version accepted")
    for event in ("push", "pull_request", "workflow_call", "workflow_dispatch"):
        assert release_request(event, "refs/heads/pixel-shizuku-rewrite", "false", "v1") == (False, "")
    assert release_request("workflow_dispatch", "refs/heads/main", "true", "pixel-11") == (True, "pixel-11")
    assert release_request("push", "refs/tags/v2.0.0", "false", "") == (True, "v2.0.0")
    for tag in ("", "other-1", "v1\ncommit=bad", "v$(echo bad)"):
        try:
            release_request("workflow_dispatch", "refs/heads/main", "true", tag)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid release tag accepted")
    repository = "Ripthulhu/librepods"
    assert trusted_signing_source(repository, "push", "refs/heads/main", "old", "tip", True)
    assert trusted_signing_source(repository, "push", "refs/tags/pixel-114", "old", "tip", True)
    assert trusted_signing_source(repository, "workflow_dispatch", "refs/heads/main", "tip", "tip", True)
    assert not trusted_signing_source(repository, "workflow_dispatch", "refs/heads/main", "old", "tip", True)
    assert not trusted_signing_source(repository, "workflow_dispatch", "refs/heads/main", "other", "tip", False)
    assert not trusted_signing_source(repository, "push", "refs/tags/pixel-114", "other", "tip", False)
    for owner, event, ref in (("someone/librepods", "push", "refs/heads/main"),
                              (repository, "pull_request", "refs/heads/main"),
                              (repository, "workflow_call", "refs/heads/main"),
                              (repository, "workflow_dispatch", "refs/heads/feature"),
                              (repository, "push", "refs/heads/feature")):
        assert not trusted_signing_source(owner, event, ref, "tip", "tip", True)
    print("Android CI metadata checks passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif sys.argv[1:]:
        raise SystemExit("Usage: android_ci_metadata.py [--self-test]")
    else:
        event, ref = os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"]
        publish, tag = release_request(event, ref,
                                       os.environ.get("REQUEST_RELEASE", "false"), os.environ.get("REQUEST_TAG", ""))
        if publish:
            subprocess.run(["git", "check-ref-format", f"refs/tags/{tag}"], check=True)
        version = app_version(Path("android/version.properties").read_text(), Path("android/app/build.gradle.kts").read_text())
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        repository = os.environ["GITHUB_REPOSITORY"]
        sign = False
        if signing_candidate(repository, event, ref):
            subprocess.run(["git", "fetch", "--no-tags", "origin", "main:refs/remotes/origin/main"], check=True)
            main_commit = subprocess.check_output(["git", "rev-parse", "refs/remotes/origin/main"], text=True).strip()
            ancestry = subprocess.run(["git", "merge-base", "--is-ancestor", commit, main_commit]).returncode
            if ancestry not in (0, 1):
                raise RuntimeError("Cannot verify signing source against main")
            sign = trusted_signing_source(repository, event, ref, commit, main_commit, ancestry == 0)
            if event == "push" and not sign:
                raise ValueError("Signed push builds must belong to main history")
        if publish and not sign:
            raise ValueError("Publishing requires a trusted main signing source; dispatch must build the current main commit")
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"commit={commit}\napp_version={version}\npublish_release={str(publish).lower()}\nrelease_tag={tag}\nsign_artifacts={str(sign).lower()}\n")
