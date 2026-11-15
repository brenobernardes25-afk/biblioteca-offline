"""Conferência local/offline dos componentes Windows do pendrive."""
import json,os,platform,sqlite3,struct,subprocess,sys,time
from pathlib import Path
APP=Path(__file__).resolve().parent; ROOT=APP.parent
sys.path.insert(0,str(APP));sys.path.insert(1,str(APP/'vendor'))

def main():
 if os.name!='nt':
  print('Este diagnóstico deve ser executado em um Windows 10/11 de 64 bits.');return 1
 results=[]
 def check(name,fn):
  try:
   detail=fn();results.append({'teste':name,'ok':True,'detalhe':str(detail)})
   print('[OK]',name,'-',detail)
  except Exception as exc:
   results.append({'teste':name,'ok':False,'detalhe':str(exc)});print('[FALHOU]',name,'-',exc)
 def bits():
  assert struct.calcsize('P')==8,'Python não é de 64 bits'
  return platform.python_version()+' / '+platform.platform()
 def fts():
  with sqlite3.connect(':memory:') as c:c.execute('CREATE VIRTUAL TABLE teste USING fts5(texto)')
  return 'SQLite FTS5 disponível'
 from biblioteca import ia,kiwix
 def exe(module):
  path=module.achar_exe()
  assert path and path.lower().endswith('.exe'),'Executável Windows não encontrado'
  r=subprocess.run([path,'--version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',timeout=45,creationflags=0x08000000)
  assert r.returncode==0,r.stdout or 'código '+str(r.returncode)
  return r.stdout.strip()[:500]
 def models():
  names=[m['arquivo'] for m in ia.modelos()]
  assert names,'Não encontrei modelos completos'
  return ', '.join(names)
 check('Python portátil x64',bits);check('Busca SQLite',fts)
 check('Motor da IA',lambda:exe(ia));check('Kiwix Windows',lambda:exe(kiwix));check('Modelos existentes',models)
 report={'hora':time.strftime('%Y-%m-%d %H:%M:%S'),'raiz':str(ROOT),'resultados':results}
 log=APP/'dados/diagnostico_windows.json';log.parent.mkdir(parents=True,exist_ok=True)
 log.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print('\nRelatório:',log)
 return int(any(not t['ok'] for t in results))
if __name__=='__main__':raise SystemExit(main())
