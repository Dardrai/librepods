#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <signal.h>

static volatile sig_atomic_t interrupted;
static void on_signal(int signal_number) { interrupted = signal_number; }
static int install_signals(void) {
    struct sigaction action = {0};
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, 0) || sigaction(SIGINT, &action, 0) || sigaction(SIGHUP, &action, 0))
        return errno;
    return 0;
}

#ifdef SIGNAL_GUARD_HOST_TEST
#include <assert.h>
#include <stdio.h>
int main(void) {
    assert(install_signals() == 0);
    const int signals[] = {SIGTERM, SIGINT, SIGHUP};
    for (unsigned i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i) {
        interrupted = 0;
        assert(raise(signals[i]) == 0 && interrupted == signals[i]);
    }
    puts("Native SIGTERM/SIGINT/SIGHUP stop flags: passed");
}
#else
#include <jni.h>
JNIEXPORT jint JNICALL Java_me_kavishdevar_librepods_headtrackerlease_HeadTrackerLease_installSignals(JNIEnv* env, jclass type) {
    (void)env; (void)type;
    return install_signals();
}
JNIEXPORT jint JNICALL Java_me_kavishdevar_librepods_headtrackerlease_HeadTrackerLease_interruptedSignal(JNIEnv* env, jclass type) {
    (void)env; (void)type;
    return interrupted;
}
#endif
