package com.aetheris.serving.service;

import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.serving.security.ServingSecurity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.concurrent.*;

@Service
public class ApiKeyOwnerValidator {
    private final AuthProtocolClient auth;
    public ApiKeyOwnerValidator(AuthProtocolClient auth){this.auth=auth;}
    public ApiKeyOwner current(TrustedUser user){var owner=ApiKeyOwner.from(user);requireActive(owner);return owner;}
    public void requireActive(ApiKeyOwner owner){
        CompletableFuture<Boolean> future=null;
        try {
            future=auth.sessionActive(owner.uid(),owner.username(),owner.accessVersion());
            if(!future.get(2500,TimeUnit.MILLISECONDS))throw new ServingSecurity.ForbiddenException("API key 绑定身份已失效，请重新申请");
        }catch(ServingSecurity.ForbiddenException rejected){throw rejected;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception unavailable){throw unavailable();}
        finally{if(future!=null&&!future.isDone())future.cancel(true);}
    }
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"身份服务暂不可用");}
}
