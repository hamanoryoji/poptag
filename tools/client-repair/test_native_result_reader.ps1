$ErrorActionPreference='Stop'
if([IntPtr]::Size -ne 4){throw 'Use 32-bit PowerShell'}
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class ResultReaderTest {
 [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint type,uint protect);
 [DllImport("kernel32.dll")] static extern bool VirtualFree(IntPtr p,UIntPtr n,uint type);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Read();
 [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Feature(int id);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate void ReadRow(IntPtr row);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Draw(int x,int y,IntPtr resource,int frame);
 [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void Badge(IntPtr surface,int x,int y,int level);
 static IntPtr Memory;static byte[] Wire;static int Position,Checks;static List<int> Frames=new List<int>();
 static IntPtr At(int n){return new IntPtr(Memory.ToInt32()+n);}
 static byte[] Hex(string s){byte[] b=new byte[s.Length/2];for(int i=0;i<b.Length;i++)b[i]=Convert.ToByte(s.Substring(2*i,2),16);return b;}
 static void Patch(byte[] b,int o,int v){Array.Copy(BitConverter.GetBytes(v),0,b,o,4);}
 static int Take(int n,int mask){if(Position+n>Wire.Length)throw new Exception("Native reader exceeded packet");int value=0;for(int j=0;j<n;j++)value=unchecked((value<<8)|Wire[Position++]);return value^mask;}
 static void Check(bool c,string label){if(!c)throw new Exception(label);Checks++;}
 public static int Test(string reader,int[] ro,int[] rt,string badge,int[] bo,int[] bt,string folder){
  Memory=VirtualAlloc(IntPtr.Zero,(UIntPtr)0x5000,0x3000,0x40);if(Memory==IntPtr.Zero)throw new Exception("Allocation");
  Read r1=new Read(()=>Take(1,0x5a)),r2=new Read(()=>Take(2,0xa569)),r4=new Read(()=>Take(4,unchecked((int)0x96ca5395)));
  Feature feature=new Feature(id=>0);Read resource=new Read(()=>At(0x3000).ToInt32()),painter=new Read(()=>1);
  Draw draw=new Draw((x,y,r,f)=>{Check(r==At(0x315c),"native badge resource");Frames.Add(f);return 1;});
  var keep=new List<Delegate>{r1,r2,r4,feature,resource,painter,draw};
  var targets=new Dictionary<int,IntPtr>{{0x78f030,Marshal.GetFunctionPointerForDelegate(r1)},{0x78f090,Marshal.GetFunctionPointerForDelegate(r2)},{0x78f100,Marshal.GetFunctionPointerForDelegate(r4)},{0x6c054e,Marshal.GetFunctionPointerForDelegate(feature)},{0x67b78f,Marshal.GetFunctionPointerForDelegate(resource)},{0x75dda0,Marshal.GetFunctionPointerForDelegate(painter)},{0x75e110,Marshal.GetFunctionPointerForDelegate(draw)}};
  try{
   byte[] r=Hex(reader);for(int i=0;i<ro.Length;i++)Patch(r,ro[i],unchecked(targets[rt[i]].ToInt32()-Memory.ToInt32()-ro[i]-4));Marshal.Copy(r,0,Memory,r.Length);
   byte[] wrapper=Hex("558bec608b7508bb e7030000 33ff e800000000 615dc20400".Replace(" ",""));
   Patch(wrapper,15,unchecked(-0x1000-19));Marshal.Copy(wrapper,0,At(0x1000),wrapper.Length);
   var run=(ReadRow)Marshal.GetDelegateForFunctionPointer(At(0x1000),typeof(ReadRow));
   foreach(string label in new[]{"loss","win"}){
    Wire=System.IO.File.ReadAllBytes(System.IO.Path.Combine(folder,label+".bin"));Position=16;
    Marshal.Copy(new byte[0x200],0,At(0x2000),0x200);run(At(0x2000));
    int exp=label=="loss"?25000:50000,gold=label=="loss"?50000:100000,level=label=="loss"?31:44;
    Check(Marshal.ReadInt32(At(0x2054))==exp,"detail experience");Check(Marshal.ReadInt32(At(0x205c))==gold,"detail gold");
    Check(Marshal.ReadInt32(At(0x2098))==gold,"per-player gold column");Check(Marshal.ReadInt32(At(0x20a8))==gold,"visible coin total");
    Check(Marshal.ReadInt32(At(0x2084))==level,"native badge level");
    Check(Marshal.ReadInt32(At(0x2088))==level,"global local-user end level");
    Check(Marshal.ReadInt32(At(0x207c))==0,"experience animation start");
    Check(Marshal.ReadInt32(At(0x2080))==(label=="loss"?156:244),"experience animation end");
    Check(Position==Wire.Length-9,"complete human row consumed without layout drift");
   }
   byte[] b=Hex(badge);for(int i=0;i<bo.Length;i++)Patch(b,bo[i],unchecked(targets[bt[i]].ToInt32()-At(0x1800).ToInt32()-bo[i]-4));Marshal.Copy(b,0,At(0x1800),b.Length);
   var render=(Badge)Marshal.GetDelegateForFunctionPointer(At(0x1800),typeof(Badge));
   foreach(int level in new[]{0,1,3,4,31,44,100,450,451,500,720,721,900}){
    Frames.Clear();render(IntPtr.Zero,10,20,level);int l=Math.Max(1,Math.Min(720,level))-1;
    int star=l%3,n=l/3,r5=n%5;n/=5;int r15=n%15;n/=15;int offset=n*23;
    Check(Frames.Count==3&&Frames[0]==offset+r5+3&&Frames[1]==offset+star&&Frames[2]==offset+r15+8,"original changing level badge frames");
   }
   GC.KeepAlive(keep);return Checks;
  }finally{VirtualFree(Memory,UIntPtr.Zero,0x8000);}
 }
}
'@
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$audit=Join-Path $root 'port-audit/result-exp-names'
$cases=Get-Content -LiteralPath (Join-Path $audit 'native-reader-cases.json') -Raw | ConvertFrom-Json
$r=$cases | Where-Object {$_.name -eq 'reader'}
$b=$cases | Where-Object {$_.name -eq 'badge'}
$passed=[ResultReaderTest]::Test($r.code,[int[]]@($r.calls|ForEach-Object {$_.offset}),[int[]]@($r.calls|ForEach-Object {$_.target}),$b.code,[int[]]@($b.calls|ForEach-Object {$_.offset}),[int[]]@($b.calls|ForEach-Object {$_.target}),(Join-Path $audit 'wire-cases'))
@{passed=$passed;gameProcessTouched=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $audit 'native-reader-test-result.json')
Write-Output "$passed native result-reader and badge assertions passed"
