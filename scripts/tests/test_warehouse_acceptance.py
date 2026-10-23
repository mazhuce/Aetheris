import copy
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('warehouse_audit',ROOT/'scripts/audit-warehouse.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
engine_spec=importlib.util.spec_from_file_location('warehouse_engine',ROOT/'scripts/warehouse/audit_spark.py')
engine=importlib.util.module_from_spec(engine_spec);engine_spec.loader.exec_module(engine)


class WarehouseAcceptanceTests(unittest.TestCase):
    def summary(self):
        required=set(re.findall(r'-- ASSERT: ([a-z0-9-]+)',(ROOT/'scripts/warehouse/06_validate.sql').read_text(encoding='utf-8')))
        names=required|audit.NEGATIVES|{'original-tables-still-pass-after-negative-views'}
        return {'purpose':'isolated-spark-warehouse-only','status':'passed','sparkVersion':'3.5.6',
                'checks':[{'case':name,'passed':True} for name in sorted(names)],
                'baseline':[{'case':name,'rejected':True} for name in ('original-date-dimension-syntax','independently-generated-order-payment')],
                'rowCounts':{str(i):10 for i in range(16)}}
    def parse(self,value):return audit.parse_summary('AETHERIS_WAREHOUSE_AUDIT='+json.dumps(value))
    def test_complete_real_engine_summary_structure_is_required(self):
        value=self.summary();self.assertEqual(value,self.parse(value))
        for mutate in (lambda x:x.update(status='failed'),lambda x:x.update(sparkVersion='old'),
                       lambda x:x['checks'].pop(),lambda x:x['checks'][0].update(case='unrelated-passing-check'),
                       lambda x:x['checks'][0].update(passed=False),lambda x:x['checks'][0].update(passed='true'),
                       lambda x:x['baseline'][0].update(rejected=False),lambda x:x['baseline'][0].update(case='unrelated-error'),
                       lambda x:x['rowCounts'].update({'0':0})):
            candidate=copy.deepcopy(value);mutate(candidate)
            with self.assertRaises(ValueError):self.parse(candidate)
    def test_missing_duplicate_or_empty_summary_cannot_pass(self):
        for text in ('','AETHERIS_WAREHOUSE_AUDIT={}\nAETHERIS_WAREHOUSE_AUDIT={}','noise only'):
            with self.assertRaises(ValueError):audit.parse_summary(text)
    def test_render_rejects_unknown_parameters(self):
        self.assertEqual('5',audit.render('@ORDER_DAYS@'))
        with self.assertRaises(ValueError):audit.render('@UNREVIEWED_PARAMETER@')
    def test_never_reuses_existing_or_external_output_directory(self):
        audit.OUTPUT.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='warehouse-test-',dir=audit.OUTPUT) as value:
            for target in (value,ROOT/'tmp'/'outside-warehouse-audit'):
                with self.assertRaises(ValueError):audit.new_directory(target)
    def test_fault_view_changes_only_the_requested_complete_table_name(self):
        sql='SELECT * FROM dwd.dwd_order_item i JOIN dwd.dwd_order o ON i.order_id=o.order_id'
        self.assertEqual('SELECT * FROM dwd.dwd_order_item i JOIN audit_order o ON i.order_id=o.order_id',
                         engine.replace_table(sql,'dwd.dwd_order','audit_order'))
    def test_unrelated_failure_cannot_masquerade_as_a_rejected_fixture(self):
        self.assertFalse(engine.assertion_failure(RuntimeError('missing table; order-line-payment in SQL text'),'order-line-payment'))
        self.assertTrue(engine.assertion_failure(RuntimeError('java.lang.RuntimeException: order-line-payment'),'order-line-payment'))


if __name__=='__main__':unittest.main()
