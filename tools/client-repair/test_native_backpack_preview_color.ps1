$ErrorActionPreference='Stop'
if([IntPtr]::Size -ne 4){throw 'Use 32-bit PowerShell'}
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class PreviewPaletteTest {
 [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint type,uint protect);
 [DllImport("kernel32.dll")] static extern bool VirtualFree(IntPtr p,UIntPtr n,uint type);
 [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void Run();
 static IntPtr Mem; static int Checks;
 static IntPtr At(int n){return new IntPtr(Mem.ToInt32()+n);}
 static byte[] Hex(string s){byte[] b=new byte[s.Length/2];for(int i=0;i<b.Length;i++)b[i]=Convert.ToByte(s.Substring(i*2,2),16);return b;}
 static void Patch(byte[] b,int o,int n){Array.Copy(BitConverter.GetBytes(n),0,b,o,4);}
 static void Put(int off,int value){Marshal.WriteInt32(At(off),value);}
 static int Get(int off){return Marshal.ReadInt32(At(off));}
 static void Check(bool c,string msg){if(!c)throw new Exception(msg);Checks++;}
 public static int Test(string code,int[] globals,int[] relOffsets,int[] relTargets){
  Mem=VirtualAlloc(IntPtr.Zero,(UIntPtr)0x40000,0x3000,0x40);if(Mem==IntPtr.Zero)throw new Exception("Allocation");
  try {
   byte[] b=Hex(code);foreach(int off in globals)Patch(b,off,At(0x3000).ToInt32());
   for(int i=0;i<relOffsets.Length;i++){
    int target=relTargets[i]==0x44b790?0x1000:relTargets[i]==0x67973e?0x1100:-1;
    Check(target>=0,"known native dependency");Patch(b,relOffsets[i],target-relOffsets[i]-4);
   }
   Marshal.Copy(b,0,Mem,b.Length);
   byte[] getter=Hex("8b01c3");Marshal.Copy(getter,0,At(0x1000),getter.Length);
   var build=new List<byte>();Action<string> emit=s=>build.AddRange(Hex(s));Action<int> val=n=>build.AddRange(BitConverter.GetBytes(n));
   emit("ff05");val(At(0x3100).ToInt32());emit("890d");val(At(0x3104).ToInt32());
   emit("8b442404a3");val(At(0x3108).ToInt32());emit("8b442408a3");val(At(0x310c).ToInt32());emit("c20800");
   Marshal.Copy(build.ToArray(),0,At(0x1100),build.Count);
   var w=new List<byte>();Action<string> e=s=>w.AddRange(Hex(s));Action<int> u=n=>w.AddRange(BitConverter.GetBytes(n));
   e("9c60be");u(At(0x10000).ToInt32());e("b811111111bb22222222ba33333333bf44444444bd5555555568460200009de8");u(-0x1200-w.Count-4);
   e("a3");u(At(0x3200).ToInt32());e("891d");u(At(0x3204).ToInt32());e("8915");u(At(0x3208).ToInt32());e("893d");u(At(0x320c).ToInt32());
   e("892d");u(At(0x3210).ToInt32());e("8935");u(At(0x3214).ToInt32());e("890d");u(At(0x3218).ToInt32());e("9c58a3");u(At(0x321c).ToInt32());e("619dc3");
   Marshal.Copy(w.ToArray(),0,At(0x1200),w.Count);var run=(Run)Marshal.GetDelegateForFunctionPointer(At(0x1200),typeof(Run));
   foreach(int slot in new[]{0,1,2})foreach(int color in new[]{0,1,3,8,9,-1})foreach(int previous in new[]{0,1,3}){
    Put(0x3000,At(0x4000).ToInt32());Put(0x29360,slot);Put(0x29c04,color);Put(0x4090,previous);Put(0x44c8,previous);Put(0x3100,0);
    run();int desired=color>=0&&color<=8?color:0;bool rebuild=slot<2&&previous!=desired;
    Check(Get(0x3100)==(rebuild?1:0),"only mismatched valid inventory needs rebuild");
    if(rebuild){Check(Get(0x3104)==At(0x4000).ToInt32(),"native manager this");Check(Get(0x3108)==slot,"inventory index");Check(Get(0x310c)==desired,"chosen palette survives equipment refresh");}
    Check(Get(0x3200)==0x11111111&&Get(0x3204)==0x22222222&&Get(0x3208)==0x33333333&&Get(0x320c)==0x44444444&&Get(0x3210)==0x55555555&&Get(0x3214)==At(0x10000).ToInt32(),"registers preserved");
    Check(Get(0x3218)==At(0x4000).ToInt32(),"original MOV ECX result");Check((Get(0x321c)&0x8d5)==(0x246&0x8d5),"arithmetic flags preserved");
   }
   Put(0x3000,0);Put(0x3100,0);run();Check(Get(0x3100)==0&&Get(0x3218)==0,"null manager safely preserves original result");
   return Checks;
  } finally {VirtualFree(Mem,UIntPtr.Zero,0x8000);}
 }
}
'@
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$audit=Join-Path $root 'port-audit/backpack-preview-color/common-adapter'
$m=Get-Content -LiteralPath (Join-Path $audit 'manifest.json') -Raw | ConvertFrom-Json
$passed=[PreviewPaletteTest]::Test($m.payload,[int[]]$m.absolute,[int[]]@($m.relative|ForEach-Object {$_.offset}),[int[]]@($m.relative|ForEach-Object {$_.target}))
@{passed=$passed;gameProcessTouched=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $audit 'native-test-result.json')
Write-Output "$passed isolated native preview palette assertions passed"
