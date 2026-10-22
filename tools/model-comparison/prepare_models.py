import requests,hashlib,json,time
from pathlib import Path
import onnx,onnxruntime as ort,numpy as np
root=Path('build/model-comparison');rev='b4f8fa610edff25e059959fb478bd3879e26f7a7'
models=[('RealESRGAN-General-x4v3','upscale-video-onnx/realesr_general_x4v3.onnx','dbb0561758e0727c76b7bf6b539a988d3b3050d51f01dcf60c842de6e6add349'),('RealESRGAN-Anime-6B','upscale-image-onnx/realesrgan_x4plus_anime_6b.onnx','1bccabc8e813ae287057c6dd342458c355e4505abe36b5e0b2b94807d39d40df')]
manifest=[]
for name,remote,expected in models:
 url=f'https://huggingface.co/Gaurox/frameshift-models/resolve/{rev}/{remote}'
 p=root/(name+'.onnx')
 if not p.exists():
  with requests.get(url,stream=True,timeout=(30,120)) as r:
   r.raise_for_status()
   with p.open('wb') as f:
    for chunk in r.iter_content(1024*1024):f.write(chunk)
 sha=hashlib.sha256(p.read_bytes()).hexdigest();assert sha==expected,(name,sha)
 onnx.checker.check_model(str(p))
 opts=ort.SessionOptions();opts.intra_op_num_threads=4
 t=time.perf_counter();s=ort.InferenceSession(str(p),sess_options=opts,providers=['CPUExecutionProvider']);load=time.perf_counter()-t
 info=s.get_inputs()[0];assert len(s.get_inputs())==len(s.get_outputs())==1 and info.type=='tensor(float)' and info.shape[1]==3
 assert not isinstance(info.shape[2],int) and not isinstance(info.shape[3],int)
 for h,w in [(16,16),(32,24),(128,128)]:
  x=np.random.default_rng(0).random((1,3,h,w),dtype=np.float32);t=time.perf_counter();y=s.run(None,{info.name:x})[0];elapsed=time.perf_counter()-t
  assert y.shape==(1,3,h*4,w*4) and np.isfinite(y).all()
  print(name,(h,w),'seconds',round(elapsed,3),flush=True)
 manifest.append(dict(name=name,file=p.name,sha256=sha,bytes=p.stat().st_size,scale=4,source=url,license='BSD-3-Clause',desktop_load_seconds=load))
for folder in ['upscale-image-onnx','upscale-video-onnx']:
 for filename in ['README.md','LICENSE-Real-ESRGAN-BSD-3-Clause.txt']:
  r=requests.get(f'https://huggingface.co/Gaurox/frameshift-models/raw/{rev}/{folder}/{filename}',timeout=30);r.raise_for_status();(root/(folder+'-'+filename)).write_text(r.text,encoding='utf-8')
(root/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps(manifest,indent=2))
