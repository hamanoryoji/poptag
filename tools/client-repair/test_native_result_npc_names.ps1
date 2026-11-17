param([string]$ManifestPath='', [switch]$AllowKun)
$ErrorActionPreference='Stop'
if([IntPtr]::Size -ne 4){throw 'Use 32-bit PowerShell'}
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class ResultNpcTest {
 [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint type,uint protect);
 [DllImport("kernel32.dll")] static extern bool VirtualFree(IntPtr p,UIntPtr n,uint type);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int GetRole(int slot);
 [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Format3(IntPtr d,IntPtr f,IntPtr s);
 [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Format4(IntPtr d,IntPtr f,IntPtr p,int number);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate void RunCase(int slot);
 static IntPtr Memory;
 static int[] Roles=new[]{20,21,22,23,20,21,22,23};
 static string Name;static int Fallback,GetterSlot,Checks;
 static IntPtr At(int offset){return new IntPtr(Memory.ToInt32()+offset);}
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);Checks++;}
 static byte[] Hex(string s){byte[] b=new byte[s.Length/2];for(int i=0;i<b.Length;i++)b[i]=Convert.ToByte(s.Substring(2*i,2),16);return b;}
 static void Patch(byte[] b,int off,int value){Array.Copy(BitConverter.GetBytes(value),0,b,off,4);}
 static string CString(IntPtr p){List<byte> b=new List<byte>();for(int n=0;n<64;n++){byte c=Marshal.ReadByte(p,n);if(c==0)break;b.Add(c);}return Encoding.GetEncoding(936).GetString(b.ToArray());}
 public static int Test(string hex,int originalBase,int[] absolute,int contextOffset,int[] relOffsets,int[] relTargets,bool kun){
  Memory=VirtualAlloc(IntPtr.Zero,(UIntPtr)0x5000,0x3000,0x40);if(Memory==IntPtr.Zero)throw new Exception("Allocation");
  GetRole get=new GetRole(s=>{GetterSlot=s;return Roles[s];});
  Format3 format=new Format3((d,f,s)=>{Check(d==At(0x3200)&&CString(f)=="%s","localized formatter arguments");Name=CString(s);return 2332;});
  Format4 fallback=new Format4((d,f,p,n)=>{Check(d==At(0x3200)&&f==At(0x3208)&&p==At(0x320c)&&n==77,"original fallback arguments");Fallback++;return 1221;});
  Dictionary<int,IntPtr> targets=new Dictionary<int,IntPtr>{{0x6860d8,Marshal.GetFunctionPointerForDelegate(get)},{0x75ab10,Marshal.GetFunctionPointerForDelegate(format)},{0x467f62,Marshal.GetFunctionPointerForDelegate(fallback)}};
  try{
   byte[] payload=Hex(hex);int delta=unchecked(Memory.ToInt32()-originalBase);
   foreach(int off in absolute)Patch(payload,off,unchecked(BitConverter.ToInt32(payload,off)+delta));
   Patch(payload,contextOffset,At(0x3000).ToInt32());
   for(int i=0;i<relOffsets.Length;i++)Patch(payload,relOffsets[i],unchecked(targets[relTargets[i]].ToInt32()-Memory.ToInt32()-relOffsets[i]-4));
   Marshal.Copy(payload,0,Memory,payload.Length);
   // Create a normal cdecl caller frame containing the original native slot argument.
   var w=new List<byte>();Action<string> emit=s=>w.AddRange(Hex(s));Action<int> u=v=>w.AddRange(BitConverter.GetBytes(v));
   emit("558bec608b4508a3");u(At(0x3108).ToInt32());emit("bd");u(At(0x3100).ToInt32());
   emit("6a4d68");u(At(0x320c).ToInt32());emit("68");u(At(0x3208).ToInt32());emit("68");u(At(0x3200).ToInt32());
   emit("e8");int callEnd=0x1000+w.Count+4;u(-callEnd);emit("83c410a3");u(At(0x3300).ToInt32());emit("615dc20400");
   Marshal.Copy(w.ToArray(),0,At(0x1000),w.Count);var run=(RunCase)Marshal.GetDelegateForFunctionPointer(At(0x1000),typeof(RunCase));
   Action<int,string> execute=(slot,want)=>{Name=null;Fallback=0;GetterSlot=-1;run(slot);Check(Name==want,"name follows actual role: got="+Name+" expected="+want+" getter="+GetterSlot);Check(Marshal.ReadInt32(At(0x3300))==(want==null?1221:2332),"return value");Check(Fallback==(want==null?1:0),"fallback count");};
   Marshal.WriteInt32(At(0x3000),At(0x2000).ToInt32());Marshal.WriteInt32(At(0x2570),16);
   string[] names={"\u6b6a\u4ed4","\u963f\u516b","\u9752\u5b9d","\u563f\u6728"};
   for(int i=0;i<4;i++){execute(4+i,names[i]);Check(GetterSlot==4+i,"original slot survives formatter's renumbering");}
   Roles[4]=21;Roles[5]=20;execute(4,"\u963f\u516b");execute(5,"\u6b6a\u4ed4");
   if(kun){Roles[4]=25;execute(4,"\u83dc\u5200");Roles[4]=26;}else Roles[4]=24;
   execute(4,null);execute(8,null);execute(-1,null);
   foreach(int mode in new[]{0,14,15}){Marshal.WriteInt32(At(0x2570),mode);execute(4,null);Check(GetterSlot==-1,"other modes untouched");}
   Marshal.WriteInt32(At(0x3000),0);execute(4,null);
   GC.KeepAlive(get);GC.KeepAlive(format);GC.KeepAlive(fallback);return Checks;
  }finally{VirtualFree(Memory,UIntPtr.Zero,0x8000);}
 }
}
'@
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$audit=Join-Path $root 'port-audit/result-exp-names/npc-adapter'
if($ManifestPath){$audit=Split-Path -Parent $ManifestPath}else{$ManifestPath=Join-Path $audit 'manifest.json'}
$m=Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$passed=[ResultNpcTest]::Test($m.payload,$m.base,[int[]]$m.absolute,$m.contextAddressOffset,[int[]]@($m.relative | ForEach-Object {$_.offset}),[int[]]@($m.relative | ForEach-Object {$_.target}),[bool]$AllowKun)
@{passed=$passed;gameProcessTouched=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $audit 'native-test-result.json')
Write-Output "$passed isolated native NPC assertions passed"
