"""Mutation run for RefreshTest: each mutation is applied to a COPY of the sources, never to the
working tree; an anchor that does not match exactly once is reported, not skipped; a mutation
the suite does not notice is reported as SURVIVED.

    python3 tools/registry-refresh-test/mutate.py <JDK_HOME> [seed ...]
"""
import subprocess, shutil, os, sys, tempfile
HERE=os.path.dirname(os.path.abspath(__file__))
SRC=os.path.normpath(os.path.join(HERE,'../../src/main/java/com/legalarchive/orchestrator'))
REG='registry/WorkflowRegistry.java'; SCH='engine/WorkflowScheduler.java'
M=[
 ('M1 written names not forced', REG, "\n                    || forced.contains(n.toLowerCase(java.util.Locale.ROOT))) {", ") {"),
 ('M2 no re-ordering', REG, "        inFileOrder(dir);\n", ""),
 ('M3 gone: contested not checked', REG, "            if (old == null) continue;\n            if (contested.contains(old.feedId)) return full(", "            if (old == null) continue;\n            if (false) return full("),
 ('M4 new: held not checked', REG, "                if (held.contains(wf.feedId)) {\n                    return full(", "                if (false) {\n                    return full("),
 ('M5 stamps ignored', REG, "if (was == null || was[0] != now[0] || was[1] != now[1]\n", "if (false\n"),
 ('M6 old error kept', REG, "            String n = f.getName();\n            dropError(n);\n", "            String n = f.getName();\n"),
 ('M7 removed workflow keeps its timer', SCH, "for (String feedId : refresh.removed) unschedule(feedId);", ""),
 ('M8 feedId change not checked', REG, "if (old.feedId == null ? wf.feedId != null : !old.feedId.equals(wf.feedId)) {", "if (false) {"),
 ('M9 set-up failures not retried', REG, "if (le.setup) return full(", "if (false) return full("),
 ('M10 unlisted directory not checked', REG, "if (!listed) return full(", "if (false) return full("),
 ('M11 stamp not updated after a read', REG, "            dropError(n);\n            seen.put(n, stamps.get(n));\n", "            dropError(n);\n"),
 ('M12 cron errors not re-ordered', SCH, "        scheduleErrors.clear();\n        scheduleErrors.putAll(ordered);\n", ""),
 ('M14 reload records no stamp', REG, "            seen.put(f.getName(), stamp(f));\n", ""),
 ('M15 parse failure: contested not checked', REG, "                if (old != null) {\n                    if (contested.contains(old.feedId)) return full(", "                if (old != null) {\n                    if (false) return full("),
 ('M16 parse failure keeps the old workflow', REG, "                if (old != null) {\n                    workflows.remove(old.feedId);\n                    layouts.remove(old.feedId);\n                    removed.add(old.feedId);\n                }\n                log.error", "                log.error"),
 ('M17 gone file keeps its workflow', REG, "            if (old != null) {\n                workflows.remove(old.feedId);\n                layouts.remove(old.feedId);\n                removed.add(old.feedId);\n                log.info", "            if (false) {\n                workflows.remove(old.feedId);\n                layouts.remove(old.feedId);\n                removed.add(old.feedId);\n                log.info"),
 ('M18 loaded workflow not rescheduled', SCH, "            if (wf != null) schedule(wf);\n        }\n        // cron", "        }\n        // cron"),
 ('M19 forced match is case-sensitive', REG, "forced.contains(n.toLowerCase(java.util.Locale.ROOT))", "forced.contains(n)"),
 ('M20 gone file keeps its error', REG, "            dropError(n);\n            seen.remove(n);", "            seen.remove(n);"),
]
jdk=sys.argv[1]
seeds=sys.argv[2:] or ['20261004']
bad=0
for name,f,old,new in M:
    tmp=tempfile.mkdtemp(); d=os.path.join(tmp,'src'); shutil.copytree(SRC,d)
    p=os.path.join(d,f); s=open(p,encoding='utf-8').read()
    if s.count(old)!=1: print('ANCHOR MISSED',name,s.count(old)); bad+=1; continue
    open(p,'w',encoding='utf-8').write(s.replace(old,new))
    killed=None
    for sd in seeds:
        r=subprocess.run([os.path.join(HERE,'run.sh'),jdk,sd,'400'],capture_output=True,text=True,env=dict(os.environ,SRC=d,OUT=os.path.join(tmp,'out')))
        if r.returncode==3: killed=('-',0,'does not compile: '+r.stdout[-200:]); bad+=1; break
        if r.returncode!=0:
            fl=[l for l in r.stdout.splitlines() if l.startswith('FAIL')]
            killed=(sd,len(fl),fl[0][:110] if fl else r.stdout[-200:]); break
    shutil.rmtree(tmp,ignore_errors=True)
    if killed: print('killed  ',name,'| seed',killed[0],'|',killed[1],'failures | first:',killed[2])
    else: print('SURVIVED',name); bad+=1
print('mutations:',len(M),'not killed:',bad)
sys.exit(1 if bad else 0)
