import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** One-time local bridge for the already-running disposable review JVM; not a production agent. */
public final class ReviewShutdownAgent {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PID, agent jar and guarded arguments required");
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String args, Instrumentation instrumentation) throws Exception {
        var values = args.split("\\|", -1);
        if (values.length != 4) throw new IllegalArgumentException("Invalid archive shutdown arguments");
        String run = values[0], container = values[1];
        if (!run.matches("[a-f0-9-]{36}") || !container.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid run/container");
        if (!System.getProperty("sun.java.command").equals("com.overload.testfixture.HomeReviewEnvironment " + run))
            throw new IllegalStateException("Only the exact original review JVM may be shut down");
        Path directory = Path.of(values[3]).toAbsolutePath().normalize();
        if (!Files.readString(directory.resolve("preserve-review-db")).trim().equals(run)) throw new IllegalStateException("Verified preservation marker required");
        var target = Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(c -> c.getName().equals("com.overload.testfixture.V22BrowserEnvironment")).findFirst().orElseThrow();
        // Only the close method body changes. JVM class-schema checks reject an incompatible build.
        instrumentation.redefineClasses(new ClassDefinition(target, Files.readAllBytes(Path.of(values[2]))));
        var reaperType = Class.forName("org.testcontainers.utility.ResourceReaper");
        var reaper = reaperType.getMethod("instance").invoke(null);
        reaperType.getMethod("unregisterContainer", String.class).invoke(reaper, container);
        Files.writeString(directory.resolve("preserve-agent-ready"), run);
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            System.exit(0); // Normal Spring/HTTP shutdown hooks, with container disposal guarded above.
        }, "review-preserving-shutdown").start();
    }
}
