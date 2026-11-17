$ErrorActionPreference='Stop'
if ([IntPtr]::Size -ne 4) { throw 'Use 32-bit PowerShell' }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class WalletLimitTest {
 [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint t,uint protect);
 [DllImport("kernel32.dll")] static extern bool VirtualFree(IntPtr p,UIntPtr n,uint t);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Fn(int property);
 static int Multiplier;
 public static int Run(string hex,int[] relocations) {
  byte[] code=new byte[hex.Length/2];for(int i=0;i<code.Length;i++)code[i]=Convert.ToByte(hex.Substring(i*2,2),16);
  IntPtr memory=VirtualAlloc(IntPtr.Zero,(UIntPtr)4096,0x3000,0x40);if(memory==IntPtr.Zero)throw new Exception("Allocation");
  Fn mock=new Fn(p=>Multiplier);IntPtr target=Marshal.GetFunctionPointerForDelegate(mock);
  try {
   foreach(int off in relocations)Array.Copy(BitConverter.GetBytes(unchecked(target.ToInt32()-memory.ToInt32()-off-4)),0,code,off,4);
   Marshal.Copy(code,0,memory,code.Length);Fn native=(Fn)Marshal.GetDelegateForFunctionPointer(memory,typeof(Fn));
   int cases=0;
   foreach(int m in new[]{0,1,2,5}) {
    Multiplier=m;if(native(0)!=99999999)throw new Exception("Normal wallet cap");cases++;
    if(native(1)!=3000000*Math.Max(1,m))throw new Exception("Original ranked multiplier");cases++;
   }
   GC.KeepAlive(mock);return cases;
  }finally{VirtualFree(memory,UIntPtr.Zero,0x8000);}
 }
}
'@
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$audit=Join-Path $root 'port-audit/local-economy/wallet-cap'
$m=Get-Content -Raw -LiteralPath (Join-Path $audit 'manifest.json') | ConvertFrom-Json
$passed=[WalletLimitTest]::Run($m.code,[int[]]@($m.relocations | ForEach-Object { $_.offset }))
@{passed=$passed;gameProcessTouched=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $audit 'native-test-result.json')
Write-Output "$passed native wallet checks passed"
