package com.aetheris.aiagent.service;

import com.aetheris.aiagent.llm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Database fencing covers replicas, process failure, cancellation and late replies. */
@Component
public class ChatRunStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public ChatRunStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; transactions=new TransactionTemplate(manager);
        transactions.setTimeout(5);
    }
    public static final class Claim {
        private final long conversationId;
        private final ChatOwner owner;
        private final UUID runId;
        private final List<LlmMessage> history;
        private Claim(long conversationId,ChatOwner owner,UUID runId,List<LlmMessage> history) {
            this.conversationId=conversationId;this.owner=Objects.requireNonNull(owner);this.runId=runId;this.history=List.copyOf(history);
        }
        public long conversationId(){return conversationId;}
        public ChatOwner owner(){return owner;}
        public UUID runId(){return runId;}
        public List<LlmMessage> history(){return history;}
        @Override public String toString(){return "ChatRunClaim[redacted]";}
    }
    public Claim claim(long id,ChatOwner owner,String question,long remainingMillis) {
        Objects.requireNonNull(owner,"Verified AI owner required");
        if(remainingMillis<1||remainingMillis>180000)throw new LlmException(LlmException.Kind.UNAVAILABLE,"会话处理时限无效或已过期",null);
        UUID run=UUID.randomUUID();
        return transactions.execute(status->{
            int claimed=jdbc.update("""
                    UPDATE ai.chat_conversation SET active_run_id=?,run_deadline=clock_timestamp()+(? * interval '1 millisecond'),
                        updated_at=clock_timestamp(),title=CASE WHEN title IS NULL OR title='' OR title='新对话' THEN ? ELSE title END
                    WHERE id=? AND owner_uid=? AND (active_run_id IS NULL OR run_deadline<clock_timestamp())
                    """,run,remainingMillis,question.replaceAll("\\s+"," ").substring(0,Math.min(30,question.replaceAll("\\s+"," ").length())),id,owner.uid());
            if(claimed!=1)throw new LlmException(LlmException.Kind.RATE_LIMITED,"会话不存在或已有请求正在处理，请稍后重试",null);
            List<LlmMessage> newest=jdbc.query("""
                    SELECT role,left(content,6000) content FROM ai.chat_message WHERE conversation_id=?
                    AND role IN ('user','assistant') ORDER BY id DESC LIMIT 20
                    """,(rs,row)->new LlmMessage(rs.getString("role"),rs.getString("content")),id);
            List<LlmMessage> history=new ArrayList<>();int chars=question.length();
            for(LlmMessage message:newest) {
                if(message.content()==null || chars+message.content().length()>20000)break;
                history.add(message);chars+=message.content().length();
            }
            Collections.reverse(history);
            jdbc.update("INSERT INTO ai.chat_message(conversation_id,role,content) VALUES(?,'user',?)",id,question);
            return new Claim(id,owner,run,List.copyOf(history));
        });
    }
    public boolean complete(Claim claim,String content) {
        return Boolean.TRUE.equals(transactions.execute(status->{
            int locked=jdbc.update("""
                    UPDATE ai.chat_conversation SET updated_at=clock_timestamp()
                    WHERE id=? AND owner_uid=? AND active_run_id=? AND run_deadline>clock_timestamp()
                    """,claim.conversationId(),claim.owner().uid(),claim.runId());
            if(locked!=1)return false;
            int inserted=jdbc.update("""
                    INSERT INTO ai.chat_message(conversation_id,role,content)
                    SELECT id,'assistant',? FROM ai.chat_conversation
                    WHERE id=? AND owner_uid=? AND active_run_id=? AND run_deadline>clock_timestamp()
                    """,content,claim.conversationId(),claim.owner().uid(),claim.runId());
            int released=jdbc.update("""
                    UPDATE ai.chat_conversation SET active_run_id=NULL,run_deadline=NULL
                    WHERE id=? AND owner_uid=? AND active_run_id=? AND run_deadline>clock_timestamp()
                    """,claim.conversationId(),claim.owner().uid(),claim.runId());
            if(inserted!=1||released!=1){status.setRollbackOnly();return false;}
            return true;
        }));
    }
    public void cancel(Claim claim) {
        transactions.executeWithoutResult(status->jdbc.update("""
                UPDATE ai.chat_conversation SET active_run_id=NULL,run_deadline=NULL
                WHERE id=? AND owner_uid=? AND active_run_id=?
                """,claim.conversationId(),claim.owner().uid(),claim.runId()));
    }
}
