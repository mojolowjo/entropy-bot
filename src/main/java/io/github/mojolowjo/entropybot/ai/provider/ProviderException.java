package io.github.mojolowjo.entropybot.ai.provider;

/** A failed model call, with a kind the loop acts on (fatal kinds stop the operator at once). */
public class ProviderException extends Exception {
    public enum Kind {
        /** 401/403: the key was refused. */
        AUTH(true),
        /** 402: no credit left. */
        NO_CREDIT(true),
        /** 404: the model id or the URL is wrong. */
        BAD_MODEL(true),
        /** A setting is wrong (no key, http:// to a remote host...). */
        CONFIG(true),
        /** Another 4xx: the request was refused. */
        REFUSED(true),
        /** 429 after the retries. */
        RATE(false),
        /** 5xx after the retries. */
        SERVER(false),
        /** Connection refused, DNS, reset. */
        NETWORK(false),
        /** No answer within the timeout. */
        TIMEOUT(false),
        /** 200 but the body was not the expected JSON. */
        BAD_RESPONSE(false);

        public final boolean fatal;

        Kind(boolean fatal) { this.fatal = fatal; }
    }

    public final Kind kind;
    /** The HTTP status (0: none). */
    public final int status;
    /** A 429's Retry-After in ms (0: none given). */
    public long retryAfterMs;

    public ProviderException(Kind kind, int status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public boolean fatal() { return kind.fatal; }

    /** Status and reason in one short line ("401: the key was refused"). */
    public String reason() { return (status > 0 ? status + ": " : "") + getMessage(); }
}
