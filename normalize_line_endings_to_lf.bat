git config core.autocrlf false
echo * text=auto eol=lf> .gitattributes
git add --renormalize .
git add .gitattributes
git commit -m "Normalize line endings to LF"
git push