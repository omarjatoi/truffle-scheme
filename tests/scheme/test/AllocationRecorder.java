package scheme.test;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.instrumentation.AllocationEvent;
import com.oracle.truffle.api.instrumentation.AllocationEventFilter;
import com.oracle.truffle.api.instrumentation.AllocationListener;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;

import scheme.runtime.staticobject.SRecord;

/**
 * A Truffle instrument for ToolsTest (plan.md M7-07): records the allocations the language reports
 * through its {@code AllocationReporter}, as the memory tracer ({@code --memtracer}) sees them: each
 * as the allocated value's class name (a record's: {@code SRecord}, whatever its generated class) and
 * its size. It listens from the moment a test looks it up.
 */
@TruffleInstrument.Registration(id = AllocationRecorder.ID, name = "Scheme test allocation recorder", services = AllocationRecorder.class)
public final class AllocationRecorder extends TruffleInstrument {

    public static final String ID = "scheme-test-allocation-recorder";

    public record Allocation(String type, long size) {
    }

    public final List<Allocation> allocations = new ArrayList<>();

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        env.getInstrumenter().attachAllocationListener(AllocationEventFilter.ANY, new AllocationListener() {
            @Override
            public void onEnter(AllocationEvent event) {
            }

            @Override
            public void onReturnValue(AllocationEvent event) {
                record(event);
            }
        });
    }

    @TruffleBoundary
    private void record(AllocationEvent event) {
        Object value = event.getValue();
        allocations.add(new Allocation(value instanceof SRecord ? "SRecord" : value.getClass().getSimpleName(), event.getNewSize()));
    }
}
