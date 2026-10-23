package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.service.ApiAssetService;
import com.aetheris.security.servlet.WorkloadProofFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.aetheris.metadata.publishedkey.PublishedKeyContract.*;

@RestController
@RequestMapping("/internal/v2/published-api-assets")
public final class PublishedKeyController {
    private final PublishedKeyQueryService service;
    public PublishedKeyController(PublishedKeyQueryService service){this.service=service;}
    @PostMapping("/resolve") public Asset resolve(HttpServletRequest request,@RequestBody byte[] raw){requireCaller(request,"metadata.published-asset.resolve");return service.resolve(resolveCode(raw));}
    @PostMapping("/key-query") public Map<String,Object> query(HttpServletRequest request,@RequestBody byte[] raw){requireCaller(request,"metadata.published-key.query");return service.query(reference(raw));}
    private static void requireCaller(HttpServletRequest request,String operation){
        try{var trusted=WorkloadProofFilter.trusted(request);var proof=trusted.workload().proof();
            if(!"serving".equals(proof.caller())||!operation.equals(proof.operation())||trusted.user().isPresent())throw new SecurityException();
        }catch(IllegalStateException absent){throw new SecurityException();}
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthenticated(){return ResponseEntity.status(401).body(Map.of("message","Authenticated serving workload required"));}
    @ExceptionHandler(Rejected.class) ResponseEntity<?> rejected(){return ResponseEntity.status(403).body(Map.of("message","Published query not authorized"));}
    @ExceptionHandler(Unavailable.class) ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","Published query dependency unavailable"));}
    @ExceptionHandler(ApiAssetService.NotFoundException.class) ResponseEntity<?> missing(){return ResponseEntity.status(404).body(Map.of("message","API asset not published"));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> malformed(){return ResponseEntity.badRequest().body(Map.of("message","Invalid published query request"));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<?> queryFailed(){return ResponseEntity.status(502).body(Map.of("message","Published query failed"));}
}
