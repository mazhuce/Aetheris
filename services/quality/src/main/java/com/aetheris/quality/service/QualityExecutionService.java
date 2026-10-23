package com.aetheris.quality.service;

import com.aetheris.quality.entity.*;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Objects;

/** Public orchestration adapter. A string subject and missing production store always deny. */
@Service
public class QualityExecutionService {
    private final QualityFixedExecutionFacade fixed;
    @Autowired public QualityExecutionService(QualityFixedExecutionFacade fixed){this.fixed=Objects.requireNonNull(fixed);}
    @Deprecated public QualityExecutionService(QualityRuleService ignored,QualityCheckResultService ignoredResults,QualityQueryClient ignoredClient,NotificationClient ignoredNotifications){this.fixed=null;}
    @Deprecated public QualityExecutionService(QualityRuleService ignored,QualityCheckResultService ignoredResults,QualityQueryClient ignoredClient,NotificationClient ignoredNotifications,QualityQuarantineService ignoredQuarantine){this.fixed=null;}
    @Deprecated public QualityCheckResult execute(Long id,String ignoredUsername){throw legacy();}
    public QualityCheckResult executeManual(Long id,TrustedUser user){if(fixed==null||id==null||id<=0)throw legacy();return fixed.manual(id,user);}
    public QualityCheckResult executeScheduled(Long id,long generation,String key){if(fixed==null||id==null||id<=0)throw legacy();return fixed.scheduled(id,generation,key);}
    private static IllegalStateException legacy(){return new IllegalStateException("执行需要可信主体、当前批准定义与持久化store/recovery，禁止旧用户名桥");}
    /** Retained pure validation entry for existing AST regression vectors. */
    String buildSql(QualityRule rule){
        if(rule==null||rule.getDatasourceId()==null||rule.getDatasourceId()<=0||rule.getThreshold()!=null&&rule.getThreshold()<0)throw new IllegalArgumentException("规则数据源或阈值无效");
        return QualitySqlGeneratorV1.generate(rule.getTargetTable(),rule.getRuleType(),rule.getExpression());
    }
    @Deprecated public static class QuarantinePersistenceException extends RuntimeException {public QuarantinePersistenceException(String message,Throwable cause){super(message,cause);}}
}
