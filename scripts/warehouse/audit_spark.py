"""Container-only warehouse fixture runner. No external catalog, network or real business data."""
import json
from pathlib import Path
import re
import sys


def statements(source):
    result=[];start=0;i=0;quote=None;line=False;block=False
    while i<len(source):
        char=source[i];following=source[i:i+2]
        if line:
            if char=='\n':line=False
        elif block:
            if following=='*/':block=False;i+=1
        elif quote:
            if char=='\\':i+=1
            elif char==quote:
                if i+1<len(source) and source[i+1]==quote:i+=1
                else:quote=None
        elif following=='--':line=True;i+=1
        elif following=='/*':block=True;i+=1
        elif char in ("'",'"','`'):quote=char
        elif char==';':
            text=source[start:i].strip()
            if text:result.append(text)
            start=i+1
        i+=1
    tail=source[start:].strip()
    if tail and re.sub(r'(?m)--.*$','',tail).strip():result.append(tail)
    if quote or block:raise ValueError('Unterminated SQL fixture')
    return result


def run():
    from pyspark.sql import SparkSession, functions as F
    if sys.argv[1:]!=['--isolated-audit'] or not Path('/audit/current/01_layers.sql').is_file():
        raise ValueError('Only run in the isolated audit container')
    spark=(SparkSession.builder.master('local[2]').appName('aetheris-isolated-warehouse-audit')
           .config('spark.ui.enabled','false').config('spark.driver.host','127.0.0.1')
           .config('spark.driver.bindAddress','127.0.0.1').config('spark.sql.shuffle.partitions','2')
           .config('spark.sql.adaptive.enabled','false')
           .config('spark.sql.warehouse.dir','file:///tmp/aetheris-warehouse')
           .config('spark.hadoop.javax.jdo.option.ConnectionURL','jdbc:derby:;databaseName=/tmp/aetheris-metastore;create=true')
           .enableHiveSupport().getOrCreate())
    spark.sparkContext.setLogLevel('WARN')
    report={'purpose':'isolated-spark-warehouse-only','status':'failed','sparkVersion':spark.version,'checks':[],'baseline':[]}
    try:
        def execute_file(folder,name):
            for sql in statements((Path('/audit')/folder/name).read_text(encoding='utf-8')):spark.sql(sql).collect()
        # Preserve original HEAD behavior as a real failing baseline, inside this private catalog only.
        old_dimension=[sql for sql in statements(Path('/audit/baseline/01_layers.sql').read_text(encoding='utf-8'))
                       if re.search(r'CREATE TABLE dim\.dim_date\s*\(',sql)]
        if len(old_dimension)!=1:raise AssertionError('Original date DDL baseline is missing')
        try:spark.sql(old_dimension[0]).collect()
        except Exception as error:
            if type(error).__name__!='ParseException':raise
            report['baseline'].append({'case':'original-date-dimension-syntax','rejected':True,'exceptionType':type(error).__name__})
        else:raise AssertionError('Expected original dimension syntax regression was not reproduced')
        execute_file('current','01_layers.sql')
        execute_file('baseline','02_ods.sql')
        execute_file('current','03_dwd.sql')
        assertions={}
        for sql in statements(Path('/audit/current/06_validate.sql').read_text(encoding='utf-8')):
            match=re.search(r'-- ASSERT: ([a-z0-9-]+)',sql)
            if match:assertions[match[1]]=sql
        if len(assertions)!=21:raise AssertionError('Frozen 21 assertion baseline differs')
        try:spark.sql(assertions['order-line-payment']).collect()
        except Exception as error:
            if not assertion_failure(error,'order-line-payment'):raise
            report['baseline'].append({'case':'independently-generated-order-payment','rejected':True,'exceptionType':type(error).__name__})
        else:raise AssertionError('Original independent payment amounts unexpectedly reconcile')
        for name in ('02_ods.sql','03_dwd.sql','04_dws.sql','05_ads.sql','06_validate.sql'):
            execute_file('current',name)
        report['checks'].extend({'case':name,'passed':True} for name in assertions)
        def fault(name,table,assertion,changes=None,empty=False):
            frame=spark.table(table)
            if changes:
                frame=frame.select(*[changes.get(field,F.col(field)).alias(field) for field in frame.columns])
            if empty:frame=frame.limit(0)
            view='audit_fault_'+name.replace('-','_');frame.createOrReplaceTempView(view)
            try:
                try:spark.sql(replace_table(assertions[assertion],table,view)).collect()
                except Exception as error:
                    if not assertion_failure(error,assertion):raise
                    report['checks'].append({'case':'reject-'+name,'passed':True,'exceptionType':type(error).__name__})
                else:raise AssertionError('Corrupt fixture was not rejected: '+name)
            finally:spark.catalog.dropTempView(view)
        fault('dws-cent','dws.dws_order_daily','dwd-dws-order-daily',{'gmv':(F.col('gmv')+F.lit('0.01').cast('decimal(18,2)')).cast('decimal(18,2)')})
        fault('ads-cent','ads.ads_order_stats','dws-ads-order-daily',{'gmv':(F.col('gmv')+F.lit('0.01').cast('decimal(18,2)')).cast('decimal(18,2)')})
        fault('category-cent','ads.ads_category_stats','dws-ads-category-monthly',{'sale_amount':(F.col('sale_amount')+F.lit('0.01').cast('decimal(18,2)')).cast('decimal(18,2)')})
        fault('share','ads.ads_category_stats','ads-category-share',{'amount_share':F.lit('0.7777').cast('decimal(6,4)')})
        fault('user-count','ads.ads_user_profile','ads-user-monthly-distinct',{'order_user_cnt':F.col('order_user_cnt')+1})
        fault('missing-summary','dws.dws_order_daily','dwd-dws-order-daily',empty=True)
        fault('missing-date','dim.dim_date','dim-date-covers-orders',empty=True)
        fault('raw-phone','dwd.dwd_user','dwd-pii-masked',{'phone_masked':F.lit('13800001234')})
        fault('raw-email','dwd.dwd_user','dwd-pii-masked',{'email':F.lit('user@example.com')})
        fault('order-cent','dwd.dwd_order','order-line-payment',{'pay_amount':(F.col('pay_amount')+F.lit('0.01').cast('decimal(12,2)')).cast('decimal(12,2)')})
        fault('line-discount','dwd.dwd_order_item','item-net-identity',{'discount':F.col('discount')+F.lit('0.01').cast('decimal(12,2)')})
        for sql in assertions.values():spark.sql(sql).collect()
        report['checks'].append({'case':'original-tables-still-pass-after-negative-views','passed':True})
        report['rowCounts']={row.table_name:row.row_cnt for row in spark.sql(statements(Path('/audit/current/06_validate.sql').read_text(encoding='utf-8'))[0]).collect()}
        report['metrics']={row.metric:str(row.amount) for row in spark.sql("""
            SELECT 'known_order_gmv' metric,COALESCE(SUM(pay_amount),0) amount FROM dwd.dwd_order WHERE is_valid_order
            UNION ALL SELECT 'valid_line_net',COALESCE(SUM(i.net_amount),0) FROM dwd.dwd_order_item i JOIN dwd.dwd_order o ON i.order_id=o.order_id AND i.dt=o.dt WHERE o.is_valid_order
            UNION ALL SELECT 'missing_payment_line_net',COALESCE(SUM(i.net_amount),0) FROM dwd.dwd_order_item i JOIN dwd.dwd_order o ON i.order_id=o.order_id AND i.dt=o.dt WHERE o.is_valid_order AND o.pay_amount IS NULL
        """).collect()}
        if any(count<=0 for count in report['rowCounts'].values()):raise AssertionError('Empty required table')
        report['status']='passed'
    except Exception as error:
        report['failureType']=type(error).__name__
        raise
    finally:
        spark.stop();print('AETHERIS_WAREHOUSE_AUDIT='+json.dumps(report,sort_keys=True),flush=True)


def assertion_failure(error,name):
    text=str(error)
    return ('java.lang.RuntimeException: '+name) in text or ('[USER_RAISED_EXCEPTION] '+name) in text


def replace_table(sql,table,view):
    return re.sub(r'\b'+re.escape(table)+r'\b',view,sql)


if __name__=='__main__':run()
