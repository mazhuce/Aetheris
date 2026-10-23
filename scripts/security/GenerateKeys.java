import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Fresh installation preparation only. Never reads/replaces installed keys. */
public final class GenerateKeys {
    private static final List<String> SERVICES=List.of("gateway","auth","system","metadata","serving","quality",
            "ingestion","governance","workflow","ai-agent");
    private static String json(String text){return "\""+text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    private static String pem(Key key,String kind){return "-----BEGIN "+kind+"-----\n"+Base64.getEncoder().encodeToString(key.getEncoded())+"\n-----END "+kind+"-----\n";}
    private static boolean posix(){return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");}
    private static void write(Path file,String text,boolean secret) throws Exception {
        var attributes=posix()?new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(secret?"rw-------":"rw-r--r--"))}:new java.nio.file.attribute.FileAttribute<?>[0];
        try(var channel=Files.newByteChannel(file,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),attributes)) {
            var bytes=java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while(bytes.hasRemaining())channel.write(bytes);
        }
    }
    public static void main(String[] args) {
        try {
            if(args.length!=1)throw new IllegalArgumentException();
            Path directory=Path.of(args[0]).toAbsolutePath().normalize();
            if(posix())Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectory(directory);
            var generator=KeyPairGenerator.getInstance("Ed25519");
            List<String> entries=new ArrayList<>(),installations=new ArrayList<>();
            String userKid=null;
            for(String service:SERVICES) {
                KeyPair pair=generator.generateKeyPair();String kid=service+"-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                write(directory.resolve(service+".workload.pem"),pem(pair.getPrivate(),"PRIVATE KEY"),true);
                entries.add("{\"kid\":"+json(kid)+",\"purpose\":\"WORKLOAD\",\"caller\":"+json(service)+",\"publicKey\":"+json(pem(pair.getPublic(),"PUBLIC KEY"))+"}");
                String extra="";
                if("gateway".equals(service)) {
                    KeyPair user=generator.generateKeyPair();userKid="gateway-user-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
                    write(directory.resolve("gateway.user.pem"),pem(user.getPrivate(),"PRIVATE KEY"),true);
                    entries.add("{\"kid\":"+json(userKid)+",\"purpose\":\"USER\",\"caller\":\"gateway\",\"publicKey\":"+json(pem(user.getPublic(),"PUBLIC KEY"))+"}");
                    extra=",\"userKid\":"+json(userKid)+",\"userPrivateFile\":\"gateway.user.pem\"";
                }
                installations.add(json(service)+":{\"workloadKid\":"+json(kid)+",\"workloadPrivateFile\":"+json(service+".workload.pem")+extra+"}");
            }
            String registry="{\"schemaVersion\":1,\"keys\":["+String.join(",",entries)+"]}\n";
            write(directory.resolve("public-registry.json"),registry,false);
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(registry.getBytes(StandardCharsets.UTF_8)));
            write(directory.resolve("installation.json"),"{\"schemaVersion\":1,\"status\":\"generated-not-installed\",\"generatedAtUtc\":"+json(Instant.now().toString())+
                    ",\"requiresPermissionsReview\":true,\"registrySha256\":"+json(fingerprint)+",\"services\":{"+String.join(",",installations)+"}}\n",false);
            System.out.println("Generated new isolated key material for "+SERVICES.size()+" services; installation and permission review remain required");
        } catch(Exception error) {
            // Partial fresh files may remain for explicit inspection. Never
            // delete/overwrite a directory supplied by an operator after failure.
            System.err.println("Key preparation failed: "+error.getClass().getSimpleName()+"; no existing key was replaced");
            System.exit(1);
        }
    }
}
