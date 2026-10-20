#!/usr/bin/env bash
# Usage: ./add_paper.sh EE2014.pdf   (needs poppler-utils: pdftoppm)
# Copies the PDF into www/papers, renders its pages to www/pages/<ID>/, and rebuilds www/papers.js
set -e
f="$1"; id=$(basename "$f" .pdf)
cp "$f" "www/papers/$id.pdf"; rm -rf "www/pages/$id"; mkdir -p "www/pages/$id"
pdftoppm -r 110 -jpeg -jpegopt quality=72 "www/papers/$id.pdf" "www/pages/$id/p"
for x in www/pages/$id/p-*.jpg; do n=$(basename "$x" .jpg); n=${n#p-}; mv "$x" "www/pages/$id/$((10#$n)).jpg"; done
python3 - <<'PY'
import json,os
from PIL import Image
items=[]
for d in sorted(os.listdir('www/pages')):
    n=len(os.listdir('www/pages/'+d)); y=d[2:6]; s=' (Set '+d.split('-')[1]+')' if '-' in d else ''
    w,h=Image.open(f'www/pages/{d}/1.jpg').size
    items.append({"id":d,"year":int(y),"title":f"GATE EE {y}{s}","pages":n,"pdf":f"papers/{d}.pdf","scanned":len(__import__("subprocess").run(["pdftotext","-l","3",f"www/papers/{d}.pdf","-"],capture_output=True,text=True).stdout.split())<50,"w":w,"h":h})
open('www/papers.js','w').write('window.PAPERS='+json.dumps(items,indent=1)+';')
print('Papers:',len(items))
PY
