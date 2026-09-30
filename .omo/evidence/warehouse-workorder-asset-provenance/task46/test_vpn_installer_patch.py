#!/usr/bin/env python3
"""Execute only the cleanup fragment against fake iptables; no real host changes."""
import importlib.util,os,subprocess,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('patcher',Path(__file__).with_name('patch-vpn-installer.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class LegacyCleanupPatch(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
  stub=self.root/'iptables';stub.write_text('''#!/bin/sh
if [ "$*" = "-t nat -S PREROUTING" ]; then
 if [ "$FIXTURE_LISTING_FAILURE" != 0 ]; then exit "$FIXTURE_LISTING_FAILURE"; fi
 cat "$FIXTURE_RULES"
else
 printf '%s\\n' "$*" >> "$FIXTURE_DELETIONS"
fi
''');stub.chmod(0o700)
 def execute(self,block,rules='',failure=0):
  source=self.root/'fragment.sh';source.write_text('set -euo pipefail\nTUN_PREFIX="10.8.0."\n'+block+'\nprintf reached > "$FIXTURE_SERVICE_MARKER"\n')
  data=self.root/'rules';data.write_text(rules)
  env=os.environ|{'PATH':str(self.root)+os.pathsep+os.environ['PATH'],'FIXTURE_RULES':str(data),'FIXTURE_LISTING_FAILURE':str(failure),'FIXTURE_DELETIONS':str(self.root/'deletions'),'FIXTURE_SERVICE_MARKER':str(self.root/'service-enabled')}
  return subprocess.run(['bash',str(source)],env=env,capture_output=True,text=True)
 def test_original_clean_host_defect_reproduced(self):
  result=self.execute(p.ORIGINAL,'-P PREROUTING ACCEPT\n');self.assertEqual(result.returncode,1);self.assertFalse((self.root/'service-enabled').exists())
 def test_corrected_clean_host_reaches_service_steps(self):
  result=self.execute(p.CORRECTED,'-P PREROUTING ACCEPT\n');self.assertEqual(result.returncode,0);self.assertTrue((self.root/'service-enabled').exists());self.assertFalse((self.root/'deletions').exists())
 def test_only_unmarked_legacy_tunnel_destination_is_deleted(self):
  rules='''-A PREROUTING -p tcp --dport 20001 -j DNAT --to-destination 10.8.0.2:8291
-A PREROUTING -p tcp --dport 20002 -m comment --comment ftth-vpn -j DNAT --to-destination 10.8.0.3:8291
-A PREROUTING -p tcp --dport 80 -j DNAT --to-destination 172.18.0.2:80
-A PREROUTING -p tcp --dport 20003 -j DNAT --to-destination 10.80.0.2:8291
'''
  result=self.execute(p.CORRECTED,rules);self.assertEqual(result.returncode,0)
  self.assertEqual((self.root/'deletions').read_text(),'-t nat -D PREROUTING -p tcp --dport 20001 -j DNAT --to-destination 10.8.0.2:8291\n')
 def test_listing_failure_aborts_before_service_steps(self):
  result=self.execute(p.CORRECTED,failure=13);self.assertEqual(result.returncode,13);self.assertFalse((self.root/'service-enabled').exists());self.assertFalse((self.root/'deletions').exists())
 def test_patcher_rejects_unknown_or_duplicate_source(self):
  for text in ('unknown',p.ORIGINAL+'\n'+p.ORIGINAL,p.CORRECTED):
   with self.assertRaises(ValueError):p.patch(text)
if __name__=='__main__':unittest.main(verbosity=2)
