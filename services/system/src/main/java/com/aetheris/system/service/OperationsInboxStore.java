package com.aetheris.system.service;

import com.aetheris.security.protocol.BodyHash;
import com.aetheris.system.dto.OperationsNotificationRequest;
import com.aetheris.system.security.NotificationOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Receiver durability and shared acknowledgement; source delivery still needs its own durable outbox. */
@Service
public class OperationsInboxStore {
    public static final Set<String> CALLERS=Set.of("workflow","quality","ingestion","metadata");
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public OperationsInboxStore(JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;transactions=new TransactionTemplate(manager);transactions.setTimeout(5);
    }
    public record Acknowledgement(int acknowledged,boolean hasMore) { }

    public long record(String caller,OperationsNotificationRequest event) {
        if(caller==null||!CALLERS.contains(caller))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Event caller rejected");
        Objects.requireNonNull(event);
        String hash=eventHash(caller,event);
        try {
            Long id=transactions.execute(tx->{
                List<Long> inserted=jdbc.queryForList("""
                        INSERT INTO system.notification(recipient,inbox_kind,source,source_id,level,title,message,link_path,event_id,event_sha256,is_read)
                        VALUES('operations','operations',?,?,?,?,?,?,?,?,false)
                        ON CONFLICT(source,event_id) DO NOTHING RETURNING id
                        """,Long.class,caller,event.sourceId(),event.level(),event.title(),event.message(),event.linkPath(),event.eventId(),hash);
                if(!inserted.isEmpty())return inserted.getFirst();
                var existing=jdbc.queryForMap("SELECT id,event_sha256,inbox_kind FROM system.notification WHERE source=? AND event_id=?",caller,event.eventId());
                if(!hash.equals(existing.get("event_sha256"))||!"operations".equals(existing.get("inbox_kind")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Event identity conflicts with stored content");
                return ((Number)existing.get("id")).longValue();
            });
            if(id==null)throw unavailable();
            return id; // execute() has committed before controller can acknowledge delivery.
        }catch(DataAccessException|TransactionException failure){throw unavailable();}
    }

    public Acknowledgement acknowledge(long id,NotificationOwner owner) {
        if(id<=0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operations event not found");
        Objects.requireNonNull(owner);
        try {
            return transactions.execute(tx->{
                int changed=jdbc.update("""
                        UPDATE system.notification SET is_read=true,ack_uid=?,ack_time=clock_timestamp()
                        WHERE id=? AND inbox_kind='operations' AND is_read=false
                        """,owner.uid(),id);
                if(changed==0&&Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system.notification WHERE id=? AND inbox_kind='operations')",Boolean.class,id)))
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Operations event not found");
                return new Acknowledgement(changed,hasMore());
            });
        }catch(DataAccessException|TransactionException failure){throw unavailable();}
    }

    public Acknowledgement acknowledgeBatch(NotificationOwner owner) {
        Objects.requireNonNull(owner);
        try {
            return transactions.execute(tx->{
                Integer changed=jdbc.queryForObject("""
                        WITH selected AS (
                          SELECT id FROM system.notification WHERE inbox_kind='operations' AND is_read=false
                          ORDER BY id LIMIT 500 FOR UPDATE SKIP LOCKED
                        ), changed AS (
                          UPDATE system.notification n SET is_read=true,ack_uid=?,ack_time=clock_timestamp()
                          FROM selected s WHERE n.id=s.id AND n.inbox_kind='operations' AND n.is_read=false RETURNING n.id
                        ) SELECT count(*)::integer FROM changed
                        """,Integer.class,owner.uid());
                return new Acknowledgement(Objects.requireNonNull(changed),hasMore());
            });
        }catch(DataAccessException|TransactionException failure){throw unavailable();}
    }
    private boolean hasMore(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM system.notification WHERE inbox_kind='operations' AND is_read=false)",Boolean.class));}
    private static String eventHash(String caller,OperationsNotificationRequest e){
        Map<String,Object> canonical=new LinkedHashMap<>();canonical.put("caller",caller);canonical.put("inbox","operations");
        canonical.put("eventId",e.eventId().toString());canonical.put("sourceId",e.sourceId());canonical.put("level",e.level());
        canonical.put("title",e.title());canonical.put("message",e.message());canonical.put("linkPath",e.linkPath());
        try{return BodyHash.sha256(JSON.writeValueAsBytes(canonical));}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid operations event");}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Operations inbox unavailable");}
}
