package demo.dispatch;

import com.staticallocationchecker.annotations.ZeroAllocations;

/**
 * The case a naive checker gets wrong: the allocation is nowhere near the annotated method.
 *
 * <p>Resolving a call site against only the type it names would find an abstract declaration with
 * an empty body, conclude "allocates nothing", and report clean. Every one of these is found by
 * following the call to the code that actually runs.
 */
public class Handlers {

    /** The contract is declared once, here. Implementations inherit it without repeating it. */
    public interface Handler {
        @ZeroAllocations
        long handle(long input);
    }

    /** CLEAN - does the work in place. */
    public static class DoublingHandler implements Handler {
        @Override
        public long handle(long input) {
            return input * 2;
        }
    }

    /**
     * VIOLATION - inherits {@code @ZeroAllocations} from the interface without repeating it, and
     * allocates. Java does not inherit annotations; the checker consults the supertype anyway.
     */
    public static class BoxingHandler implements Handler {
        @Override
        public long handle(long input) {
            Long boxed = input;
            return boxed;
        }
    }

    /** Declares a helper that subclasses inherit rather than override. */
    public static class BaseProcessor {
        /** VIOLATION - found through the subclass that inherits it. */
        Object shared() {
            return new Object();
        }
    }

    /** Inherits {@code shared()} unchanged. */
    public static class DerivedProcessor extends BaseProcessor {
    }

    private final Handler handler = new BoxingHandler();
    private final DerivedProcessor processor = new DerivedProcessor();

    /**
     * The call site allocates nothing itself. The finding is attributed to the implementation that
     * does, with the call path showing how it was reached.
     */
    @ZeroAllocations
    public long dispatch(long input) {
        return handler.handle(input);
    }

    /** Calls a method declared on a supertype, resolved by climbing the hierarchy. */
    @ZeroAllocations
    public Object inherited() {
        return processor.shared();
    }
}
