#!/usr/bin/env python3
"""
Lists the constructs through which a platform leaks into behaviour, per source file.

Used for the Linux audit of 2026-10-04 (.claude/LINUX_AUDIT.md). It FINDS candidates; it does not
judge them: a literal CRLF written to a CSV is platform-neutral, File.renameTo is not. Read the hits.
Comments are stripped first, string literals are kept.

usage: python3 tools/scan_platform_assumptions.py <src/main/java root> [category letter | path fragment]
       no second argument: a count per file and category
       A separators  B line endings  C default charset  D case  E Windows  F paths  G file operations
"""
import io,os,re,sys,collections
if len(sys.argv) < 2: sys.exit(__doc__)
ROOT=os.path.join(sys.argv[1],'com','legalarchive','orchestrator','')
sys.argv=[sys.argv[0]]+sys.argv[2:]
def strip(src):
    out=[];i=0;n=len(src)
    while i<n:
        c=src[i];d=src[i+1] if i+1<n else ''
        if c=='"':
            j=i+1
            while j<n and src[j]!='"': j+=2 if src[j]=='\\' else 1
            out.append(src[i:j+1]); i=j+1
        elif c=="'" :
            j=i+1
            while j<n and src[j]!="'": j+=2 if src[j]=='\\' else 1
            out.append(src[i:j+1]); i=j+1
        elif c=='/' and d=='/':
            while i<n and src[i]!='\n': i+=1
        elif c=='/' and d=='*':
            j=src.find('*/',i+2); j=n if j<0 else j+2
            out.append(re.sub(r'[^\n]','',src[i:j])); i=j
        else: out.append(c); i+=1
    return ''.join(out)
CATS=collections.OrderedDict([
 ('A.separator', r'File\.separator|separatorChar|"\\\\\\\\"|\'\\\\\\\\\'|replace\(\s*\'/\'\s*,|replace\(\s*"/"\s*,|replace\(\s*\'\\\\\\\\\'|replace\(\s*"\\\\\\\\"'),
 ('B.lineending', r'lineSeparator\(\)|\.newLine\(\)|"[^"]*\\r\\n[^"]*"|\.println\(|%n'),
 ('C.charset', r'new String\(\s*[A-Za-z_.]+\s*\)|\.getBytes\(\s*\)|new FileReader\(|new FileWriter\(|new InputStreamReader\(\s*[^,()]+(\([^()]*\))?\s*\)|new OutputStreamWriter\(\s*[^,()]+(\([^()]*\))?\s*\)|new PrintWriter\(\s*(new File|[a-z][A-Za-z]*\s*\))|new Scanner\(|defaultCharset\(\)|new PrintStream\('),
 ('E.windows', r'[Ww]indows|"dos:|"cmd|\.exe"|"[A-Za-z]:\\\\\\\\|setReadOnly\(|Runtime\.getRuntime\(\)\.exec|new ProcessBuilder'),
 ('F.paths', r'"user\.dir"|"java\.io\.tmpdir"|"user\.home"|\[A-Za-z\]:|isAbsolute\(\)|"\\\\\\\\\\\\\\\\'),
 ('G.fileops', r'ATOMIC_MOVE|\.renameTo\(|setExecutable\(|setLastModified\(|Posix|deleteOnExit\(|setWritable\(|setReadable\('),
 ('D.case', r'getName\(\)\.equalsIgnoreCase|getFileName\(\)\.toString\(\)\.equalsIgnoreCase|\.toLowerCase\(\)\.endsWith\(|toLowerCase\(\)\.equals\('),
])
want=sys.argv[1] if len(sys.argv)>1 else None
tot=collections.Counter()
for dp,dn,fn in os.walk(ROOT):
    for f in sorted(fn):
        if not f.endswith('.java'): continue
        p=os.path.join(dp,f); rel=p[len(ROOT):]
        code=strip(io.open(p,encoding='utf-8').read()).split('\n')
        for k,rx in CATS.items():
            for i,l in enumerate(code):
                if re.search(rx,l):
                    tot[(k,rel)]+=1
                    if want and (want==k[0] or want in rel): print('%s %s:%d  %s'%(k[0],rel,i+1,l.strip()[:150]))
if not want:
    by=collections.defaultdict(dict)
    for (k,rel),n in tot.items(): by[rel][k[0]]=n
    print('file'.ljust(38),' '.join(c[0] for c in CATS))
    for rel in sorted(by, key=lambda r:-sum(by[r].values())):
        print(rel.ljust(38),' '.join(str(by[rel].get(c[0],'.')).rjust(1) for c in CATS))
