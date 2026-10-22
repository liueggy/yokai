import json,hashlib,time,requests
from pathlib import Path
import torch
from torch import nn
import torch.nn.functional as F
import onnx,onnxruntime as ort,numpy as np
root=Path(__file__).parent
revision="cc385f97a9debfe611316aabfd5d8bb30ba2dbeb"
url=f"https://raw.githubusercontent.com/nagadomi/waifu2x/{revision}/models/upconv_7/art/noise1_scale2.0x_model.json"
r=requests.get(url,timeout=60);r.raise_for_status()
(root/'noise1_scale2.0x_model.json').write_bytes(r.content)
layers=json.loads(r.content)
class Model(nn.Module):
 def __init__(self):
  super().__init__();self.layers=nn.ModuleList()
  for info in layers:
   if info['class_name']=='nn.SpatialFullConvolution':
    conv=nn.ConvTranspose2d(info['nInputPlane'],info['nOutputPlane'],(info['kH'],info['kW']),stride=2,padding=info['padW'])
   else:
    conv=nn.Conv2d(info['nInputPlane'],info['nOutputPlane'],(info['kH'],info['kW']))
   conv.weight.data.copy_(torch.tensor(info['weight'],dtype=torch.float32));conv.bias.data.copy_(torch.tensor(info['bias'],dtype=torch.float32));self.layers.append(conv)
 def forward(self,x):
  x=F.pad(x,(7,7,7,7),mode='replicate')
  for i,conv in enumerate(self.layers):
   x=conv(x)
   if i<len(self.layers)-1:x=F.leaky_relu(x,0.1)
  return x.clamp(0,1)
net=Model().eval();torch.set_num_threads(4)
path=root/'waifu2x-noise1-2x.onnx'
x=torch.rand(1,3,32,32)
torch.onnx.export(net,x,str(path),opset_version=17,input_names=['input'],output_names=['output'],dynamic_axes={'input':{0:'batch',2:'height',3:'width'},'output':{0:'batch',2:'height2',3:'width2'}},dynamo=False)
onnx.checker.check_model(str(path))
opts=ort.SessionOptions();opts.intra_op_num_threads=4
session=ort.InferenceSession(str(path),sess_options=opts,providers=['CPUExecutionProvider'])
assert session.get_inputs()[0].type=='tensor(float)'
results=[]
for h,w in [(1,1),(16,16),(32,24),(128,128),(256,256)]:
 x=torch.rand(1,3,h,w)
 with torch.no_grad():expected=net(x).numpy()
 t=time.perf_counter();actual=session.run(None,{'input':x.numpy()})[0];elapsed=time.perf_counter()-t
 assert actual.shape==(1,3,h*2,w*2) and np.isfinite(actual).all()
 np.testing.assert_allclose(actual,expected,atol=1e-4,rtol=1e-4)
 results.append({'input':[h,w],'seconds':elapsed,'max_abs_error':float(np.max(np.abs(actual-expected)))})
 print(results[-1],flush=True)
meta={'name':'waifu2x','architecture':'upconv_7/art/noise1_scale2.0x','source':url,'source_sha256':hashlib.sha256(r.content).hexdigest(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size,'scale':2,'license':'MIT','export':'opset17; replicate pad7 matching upstream clamp edges; leaky_relu0.1; unchanged JSON weights; final clamp0..1','tests':results}
(root/'manifest.json').write_text(json.dumps(meta,indent=2))
print(json.dumps(meta,indent=2))
