import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Isolated compatibility runner. No application startup and no raw test/key output. */
public final class RunProtocolTests {
    public static void main(String[] args) throws Exception {
        PrintStream originalOut=System.out,originalErr=System.err;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("startedAtUtc",Instant.now().toString());
        result.put("javaRuntimeVersion",System.getProperty("java.runtime.version"));
        result.put("javaVendor",System.getProperty("java.vendor"));
        result.put("javaSpecificationVersion",System.getProperty("java.specification.version"));
        result.put("osName",System.getProperty("os.name"));
        result.put("osArch",System.getProperty("os.arch"));
        result.put("status","failed");
        int exit=1;
        try(var suppressed=new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(suppressed);System.setErr(suppressed);
            if(args.length!=1 || !args[0].matches("[1-9][0-9]{0,5}"))throw new IllegalArgumentException();
            int expected=Integer.parseInt(args[0]);
            if(!"21".equals(System.getProperty("java.specification.version")))throw new IllegalStateException();
            var request=LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectPackage("com.aetheris.security.protocol")).build();
            Launcher launcher=LauncherFactory.create();
            TestPlan plan=launcher.discover(request);
            long initial=plan.countTestIdentifiers(TestIdentifier::isTest);
            result.put("initialDiscoveredTests",initial);
            if(initial==0)throw new IllegalStateException();
            var listener=new SummaryGeneratingListener();launcher.registerTestExecutionListeners(listener);
            launcher.execute(plan);
            TestExecutionSummary summary=listener.getSummary();
            result.put("expectedTests",expected);result.put("found",summary.getTestsFoundCount());
            result.put("succeeded",summary.getTestsSucceededCount());result.put("failed",summary.getTestsFailedCount());
            result.put("skipped",summary.getTestsSkippedCount());result.put("aborted",summary.getTestsAbortedCount());
            result.put("containersFailed",summary.getContainersFailedCount());
            result.put("containersSkipped",summary.getContainersSkippedCount());
            result.put("containersAborted",summary.getContainersAbortedCount());
            Map<String,Long> failures=new TreeMap<>();
            for(var failure:summary.getFailures())failures.merge(failure.getException().getClass().getSimpleName(),1L,Long::sum);
            result.put("failureClasses",failures);
            boolean passed=summary.getTestsFoundCount()==expected && summary.getTestsSucceededCount()==expected
                    && summary.getTestsFailedCount()==0 && summary.getTestsSkippedCount()==0 && summary.getTestsAbortedCount()==0
                    && summary.getContainersFailedCount()==0 && summary.getContainersSkippedCount()==0 && summary.getContainersAbortedCount()==0;
            if(passed){result.put("status","passed");exit=0;}
        }catch(Throwable error){result.put("runnerErrorClass",error.getClass().getSimpleName());}
        finally{System.setOut(originalOut);System.setErr(originalErr);}
        result.put("completedAtUtc",Instant.now().toString());
        originalOut.println("AETHERIS_PROTOCOL_TESTS="+new ObjectMapper().writeValueAsString(result));
        System.exit(exit);
    }
}
