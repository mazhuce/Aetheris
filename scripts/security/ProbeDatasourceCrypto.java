import com.aetheris.metadata.service.DataSourceSecretService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/** Isolated compatibility probe. Input/output may contain fixtures: never persist its streams. */
public class ProbeDatasourceCrypto {
    public static void main(String[] args) {
        try {
            var input=new Properties();input.load(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8));
            var service=new DataSourceSecretService(input.getProperty("key"));
            String value=new String(Base64.getDecoder().decode(input.getProperty("value")),StandardCharsets.UTF_8);
            String result=switch(input.getProperty("operation")){case "encrypt"->service.encrypt(value);case "decrypt"->service.decrypt(value);default->throw new IllegalArgumentException();};
            System.out.println(Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception failure){System.err.println("Crypto fixture probe failed; details withheld");System.exit(1);}
    }
}
