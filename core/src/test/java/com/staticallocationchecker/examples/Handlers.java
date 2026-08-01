package com.staticallocationchecker.examples;

/**
 * Allocation reached through dispatch and inheritance, so it is nowhere near the entry point.
 *
 * <p>Resolving a call site against only the type it names finds an abstract declaration with an
 * empty body, concludes "allocates nothing", and reports clean - the most dangerous answer a
 * verification tool can give, because it looks like success.
 */
public class Handlers {

    public interface Handler {
        long handle(long input);
    }

    /** Clean: does the work in place. */
    public static class DoublingHandler implements Handler {
        @Override
        public long handle(long input) {
            return input * 2;
        }
    }

    /** Allocates, and is only reachable through the interface. */
    public static class BoxingHandler implements Handler {
        @Override
        public long handle(long input) {
            Long boxed = input;
            return boxed;
        }
    }

    /** Declares a method that subclasses inherit rather than override. */
    public static class BaseProcessor {
        Object shared() {
            return new Object();
        }
    }

    /** Inherits {@code shared()} unchanged, so the call site names this type. */
    public static class DerivedProcessor extends BaseProcessor {
    }

    private final Handler handler = new BoxingHandler();
    private final DerivedProcessor processor = new DerivedProcessor();

    public long dispatch(long input) {
        return handler.handle(input);
    }

    public Object inherited() {
        return processor.shared();
    }
}
