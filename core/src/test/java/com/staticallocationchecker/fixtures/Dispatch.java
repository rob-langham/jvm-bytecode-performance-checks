package com.staticallocationchecker.fixtures;

import com.staticallocationchecker.annotations.ZeroAllocations;

/** Fixture: allocation reached through virtual and interface dispatch. */
public class Dispatch {

    /** Implemented by an allocating class below. */
    public interface Handler {
        Object handle();
    }

    /** The allocation lives here, not at the annotated call site. */
    public static class AllocatingHandler implements Handler {
        @Override
        public Object handle() {
            return new Object();
        }
    }

    /** An abstract superclass whose override allocates. */
    public abstract static class Base {
        abstract Object make();
    }

    /** The allocating override. */
    public static class Impl extends Base {
        @Override
        Object make() {
            return new Object();
        }
    }

    private final Handler handler = new AllocatingHandler();
    private final Base base = new Impl();

    @ZeroAllocations
    public Object throughInterface() {
        return handler.handle();
    }

    @ZeroAllocations
    public Object throughAbstractClass() {
        return base.make();
    }

    /** Dispatch through an interface whose implementations are outside the analysis roots. */
    @ZeroAllocations
    public int throughUnindexedInterface(java.util.List<String> values) {
        return values.size();
    }
}
