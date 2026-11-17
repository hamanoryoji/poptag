$ErrorActionPreference='Stop'
if([IntPtr]::Size -ne 4){throw 'Use 32-bit PowerShell'}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class LevelExtensionTest {
 [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint t,uint f);
 [DllImport("kernel32.dll")] static extern bool VirtualFree(IntPtr p,UIntPtr n,uint t);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Run(int level);
 [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Draw(int surface,int x,int y,int level);
 static IntPtr Memory;static int Captured,Checks;
 static IntPtr At(int n){return new IntPtr(Memory.ToInt32()+n);}
 static byte[] Hex(string s){var b=new byte[s.Length/2];for(int i=0;i<b.Length;i++)b[i]=Convert.ToByte(s.Substring(i*2,2),16);return b;}
 static void Put(byte[] b,int o,int v){Array.Copy(BitConverter.GetBytes(v),0,b,o,4);}
 static void Check(bool c,string s){if(!c)throw new Exception(s);Checks++;}
 public static int Test(string code,int callOffset,int jumpOffset){
  Memory=VirtualAlloc(IntPtr.Zero,(UIntPtr)0x5000,0x3000,0x40);if(Memory==IntPtr.Zero)throw new Exception("allocation");
  Draw draw=new Draw((surface,x,y,level)=>{Check(surface==0x1111&&x==10&&y==20,"extended renderer arguments");Captured=level;return 2332;});
  try{
   byte[] adapter=Hex(code);Put(adapter,callOffset,unchecked(Marshal.GetFunctionPointerForDelegate(draw).ToInt32()-Memory.ToInt32()-callOffset-4));Put(adapter,jumpOffset,0x2000-jumpOffset-4);Marshal.Copy(adapter,0,Memory,adapter.Length);
   byte[] legacy=Hex("8b4514a3000000008bc1a300000000b8d20400005dc21000");Put(legacy,4,At(0x3000).ToInt32());Put(legacy,11,At(0x3004).ToInt32());Marshal.Copy(legacy,0,At(0x2000),legacy.Length);
   byte[] wrapper=Hex("558becff75086a146a0a6811110000b934120000e8000000005dc20400");Put(wrapper,21,unchecked(-0x1000-25));Marshal.Copy(wrapper,0,At(0x1000),wrapper.Length);
   var run=(Run)Marshal.GetDelegateForFunctionPointer(At(0x1000),typeof(Run));
   foreach(int level in new[]{0,1,44,450,451,500,720,721,900}){
    Captured=-1;Marshal.WriteInt32(At(0x3000),-1);int result=run(level);
    Check(result==(level<=450?1234:2332),"branch and stack return");
    if(level<=450){Check(Marshal.ReadInt32(At(0x3000))==level,"legacy input preserved");Check(Marshal.ReadInt32(At(0x3004))==0x1234,"legacy this pointer preserved");}
    else Check(Captured==level,"extended level passed intact");
   }
   GC.KeepAlive(draw);return Checks;
  }finally{VirtualFree(Memory,UIntPtr.Zero,0x8000);}
 }
}
'@
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$audit=Join-Path $root 'port-audit/npc-level-restore-v2'
$m=Get-Content (Join-Path $audit 'manifest.json') -Raw | ConvertFrom-Json
$passed=[LevelExtensionTest]::Test($m.code,$m.relative[0].offset,$m.legacyJumpOffset)
@{passed=$passed;gameProcessTouched=$false} | ConvertTo-Json | Set-Content (Join-Path $audit 'native-test-result.json')
Write-Output "$passed isolated level-extension assertions passed"
