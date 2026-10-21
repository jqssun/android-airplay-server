"""Exercise the patched native stop function with a blocked frame callback.

Compile the actual function body against a minimal pthread harness, so this test
does not require an Android device or UxPlay's unrelated protocol dependencies.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest


class MirrorStopTest(unittest.TestCase):
    def test_cancels_blocked_callback_before_join(self):
        root = Path(__file__).resolve().parents[1]
        native = root / "app/src/main/cpp"
        # Apply the distributed patch to the pinned source, independently of any
        # local build's generated submodule changes.
        with tempfile.TemporaryDirectory() as folder:
            tree = Path(folder)
            (tree / "lib").mkdir()
            for name in ("raop.h", "raop_rtp_mirror.c"):
                original = subprocess.check_output([
                    "git", "-C", str(native / "third_party/UxPlay"),
                    "show", "HEAD:lib/" + name])
                (tree / "lib" / name).write_bytes(original)
            subprocess.run(["git", "apply", str(native / "patches/UxPlay/0011-cancel-renderer-before-mirror-join.patch")],
                           cwd=tree, check=True)
            source = (tree / "lib/raop_rtp_mirror.c").read_text()
        start = source.index("void raop_rtp_mirror_stop(")
        opening = source.index("{", start)
        depth = 1
        end = opening + 1
        while depth:
            depth += (source[end] == "{") - (source[end] == "}")
            end += 1
        harness = r'''
#include <assert.h>
#include <pthread.h>
#include <unistd.h>
#define MUTEX_LOCK(m) pthread_mutex_lock(&(m))
#define MUTEX_UNLOCK(m) pthread_mutex_unlock(&(m))
#define THREAD_JOIN(t) pthread_join((t), NULL)
#define CLOSESOCKET(s) close(s)
typedef struct {
    pthread_mutex_t run_mutex;
    int running, joined, mirror_data_sock;
    pthread_t thread_mirror;
    struct { void *cls; void (*mirror_video_stopping)(void *); } callbacks;
} raop_rtp_mirror_t;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static int entered, cancelled, exited;
static void *blocked_frame(void *unused) {
    pthread_mutex_lock(&lock);
    entered = 1;
    pthread_cond_broadcast(&changed);
    while (!cancelled) pthread_cond_wait(&changed, &lock);
    exited = 1;
    pthread_mutex_unlock(&lock);
    return NULL;
}
static void cancel_frame(void *unused) {
    pthread_mutex_lock(&lock);
    assert(!exited);
    cancelled = 1;
    pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&lock);
}
'''
        main = r'''
int main(void) {
    raop_rtp_mirror_t mirror = {0};
    pthread_mutex_init(&mirror.run_mutex, NULL);
    mirror.running = 1;
    mirror.mirror_data_sock = -1;
    mirror.callbacks.mirror_video_stopping = cancel_frame;
    pthread_create(&mirror.thread_mirror, NULL, blocked_frame, NULL);
    pthread_mutex_lock(&lock);
    while (!entered) pthread_cond_wait(&changed, &lock);
    pthread_mutex_unlock(&lock);
    raop_rtp_mirror_stop(&mirror);
    assert(cancelled && exited && mirror.joined && !mirror.running);
    /* A repeated stop must not call the callback against an exited thread. */
    raop_rtp_mirror_stop(&mirror);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / "stop.c").write_text(harness + source[start:end] + main)
            subprocess.run(["cc", "-pthread", str(path / "stop.c"), "-o", str(path / "stop")], check=True)
            subprocess.run([str(path / "stop")], check=True, timeout=2)


if __name__ == "__main__":
    unittest.main()
