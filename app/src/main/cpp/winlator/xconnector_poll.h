#ifndef WINLATOR_XCONNECTOR_POLL_H
#define WINLATOR_XCONNECTOR_POLL_H

#include <errno.h>
#include <poll.h>
#include <stdbool.h>

enum xconnector_poll_action {
    XCONNECTOR_POLL_TERMINATE = 0,
    XCONNECTOR_POLL_DISPATCH = 1 << 0,
    XCONNECTOR_POLL_CONTINUE = 1 << 1,
};

static inline int xconnector_poll_wait(struct pollfd *fds, nfds_t count, int timeout) {
    int result;
    do {
        result = poll(fds, count, timeout);
    } while (result < 0 && errno == EINTR);
    return result;
}

static inline unsigned int xconnector_classify_poll_events(short client_revents,
                                                            short shutdown_revents) {
    if (shutdown_revents != 0) {
        return XCONNECTOR_POLL_TERMINATE;
    }

    const short terminal_events = POLLHUP | POLLERR | POLLNVAL;
    const bool readable = (client_revents & POLLIN) != 0;
    const bool terminal = (client_revents & terminal_events) != 0;

    if (readable) {
        return XCONNECTOR_POLL_DISPATCH |
               (terminal ? XCONNECTOR_POLL_TERMINATE : XCONNECTOR_POLL_CONTINUE);
    }

    // A positive poll result without a readable client must never re-enter the
    // Java loop. This covers terminal and otherwise unexpected event masks.
    return XCONNECTOR_POLL_TERMINATE;
}

#endif
