#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int exec_error_status(int error)
{
    return error == ENOENT ? 127 : 126;
}

static int report_error(const char *operation, const char *target, int error)
{
    fprintf(stderr, "libsigquit-exec.so: %s %s: %s\n",
            operation, target, strerror(error));
    return exec_error_status(error);
}

int main(int argc, char **argv)
{
    sigset_t signals;

    if (argc < 2)
    {
        fprintf(stderr, "usage: libsigquit-exec.so <program> [args...]\n");
        return 64;
    }

    if (sigemptyset(&signals) < 0)
        return report_error("sigemptyset for", argv[1], errno);
    if (sigaddset(&signals, SIGQUIT) < 0)
        return report_error("sigaddset for", argv[1], errno);
    if (sigprocmask(SIG_UNBLOCK, &signals, NULL) < 0)
        return report_error("sigprocmask for", argv[1], errno);

    execvp(argv[1], &argv[1]);
    return report_error("exec", argv[1], errno);
}
