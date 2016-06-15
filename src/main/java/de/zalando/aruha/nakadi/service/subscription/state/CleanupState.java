package de.zalando.aruha.nakadi.service.subscription.state;

public class CleanupState extends State {
    private final Exception exception;

    public CleanupState(final Exception e) {
        this.exception = e;
    }

    CleanupState() {
        this(null);
    }

    @Override
    public void onEnter() {
        try {
            if (null != exception) {
                getOut().onException(exception);
            }
        } finally {
            try {
                unregisterSession();
            } finally {
                switchState(null);
            }
        }
    }
}
