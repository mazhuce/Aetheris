import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

/** Audit-only: stdin refers to this run's fresh temporary keys, never installed keys. Stdout is a secret. */
public final class SignIsolatedUser {
    public static void main(String[] args) {
        try {
            byte[] bytes=System.in.readNBytes(16385);
            if(bytes.length==0||bytes.length>16384)throw new IllegalArgumentException();
            var json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var input=json.readTree(bytes);
            var fields=Set.of("registry","workloadKid","workloadFile","userKid","userFile","uid","username","accessVersion","audience");
            if(!input.isObject()||input.size()!=fields.size())throw new IllegalArgumentException();
            input.fieldNames().forEachRemaining(name->{if(!fields.contains(name))throw new IllegalArgumentException();});
            for(String name:fields)if(!name.equals("uid")&&!name.equals("accessVersion")&&!input.get(name).isTextual())throw new IllegalArgumentException();
            for(String name:Set.of("uid","accessVersion"))if(!input.get(name).isIntegralNumber()||!input.get(name).canConvertToLong())throw new IllegalArgumentException();
            Path registry=Path.of(input.get("registry").asText()).toAbsolutePath().normalize();
            Path workload=Path.of(input.get("workloadFile").asText()).toAbsolutePath().normalize();
            Path user=Path.of(input.get("userFile").asText()).toAbsolutePath().normalize();
            Path directory=registry.getParent();
            if(!directory.getFileName().toString().equals("keys")||!directory.getParent().getFileName().toString().matches("audit-identity-[0-9a-f]{32}")
                    ||!workload.getParent().equals(directory)||!user.getParent().equals(directory)
                    ||!registry.getFileName().toString().equals("public-registry.json")||!workload.getFileName().toString().equals("gateway.workload.pem")
                    ||!user.getFileName().toString().equals("gateway.user.pem"))throw new IllegalArgumentException();
            var clock=Clock.systemUTC();long now=clock.instant().getEpochSecond();
            var keys=ProtocolInstallation.load("gateway",registry,input.get("workloadKid").asText(),workload,input.get("userKid").asText(),user,clock);
            System.out.print(keys.userSigner().orElseThrow().sign(new UserProof(2,"gateway",input.get("audience").asText(),
                    input.get("uid").asLong(),input.get("username").asText(),input.get("accessVersion").asLong(),now,now+120,"isolated-user-probe")));
        }catch(Exception error){System.err.print("Isolated USER fixture failed; secret output withheld");System.exit(1);}
    }
}
