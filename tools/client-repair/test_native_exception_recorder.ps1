$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 4) { throw 'This self-test requires 32-bit Windows PowerShell.' }
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
public static class RecorderSelfTest {
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr VirtualAlloc(IntPtr a, UIntPtr n, uint type, uint protect);
    [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string name);
    [DllImport("kernel32.dll")] static extern IntPtr GetProcAddress(IntPtr module, string name);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Callback(IntPtr exceptionPointers);
    static IntPtr At(IntPtr address, int offset) { return new IntPtr(address.ToInt32()+offset); }
    static void Pointer(IntPtr address, IntPtr value) { Marshal.WriteInt32(address, value.ToInt32()); }
    public static void Run(byte[] code, string destination) {
        IntPtr allocation=VirtualAlloc(IntPtr.Zero,new UIntPtr(0x10000),0x3000,0x40);
        IntPtr imports=VirtualAlloc(IntPtr.Zero,new UIntPtr(0x10000),0x3000,0x04);
        if (allocation==IntPtr.Zero || imports==IntPtr.Zero) throw new Exception("Cannot reserve isolated test buffers");
        uint[] targets={0xb95540,0xb95530,0x8892a0,0x8891f4,0x8892f4,0x8892a4};
        for (int i=0;i<=code.Length-4;i++) {
            uint value=BitConverter.ToUInt32(code,i);
            foreach(uint target in targets) if(value==target) {
                IntPtr address=value>=0xb90000 ? At(allocation,(int)value-0xb90000) : At(imports,(int)value-0x880000);
                Array.Copy(BitConverter.GetBytes(address.ToInt32()),0,code,i,4);i+=3;break;
            }
        }
        IntPtr kernel=GetModuleHandle("kernel32.dll");
        Pointer(At(imports,0x92a0),GetProcAddress(kernel,"CreateFileA"));
        Pointer(At(imports,0x91f4),GetProcAddress(kernel,"WriteFile"));
        Pointer(At(imports,0x92f4),GetProcAddress(kernel,"FlushFileBuffers"));
        Pointer(At(imports,0x92a4),GetProcAddress(kernel,"CloseHandle"));
        byte[] name=System.Text.Encoding.ASCII.GetBytes(destination+"\0");
        Marshal.Copy(name,0,At(allocation,0x5540),name.Length);
        Marshal.Copy(code,0,At(allocation,0x5900),code.Length);
        IntPtr record=Marshal.AllocHGlobal(80),context=Marshal.AllocHGlobal(716),pointers=Marshal.AllocHGlobal(8),stack=Marshal.AllocHGlobal(1024);
        Marshal.Copy(new byte[80],0,record,80);Marshal.Copy(new byte[716],0,context,716);
        Marshal.WriteInt32(record,unchecked((int)0xc0000005));
        Marshal.WriteInt32(record,12,unchecked((int)0xdeadbeef));
        Marshal.WriteInt32(context,184,unchecked((int)0xdeadbeef));
        Marshal.Copy(new byte[1024],0,stack,1024);Marshal.WriteInt32(stack,0x12345678);
        Marshal.WriteIntPtr(context,196,stack);
        IntPtr collided=Marshal.AllocHGlobal(832),scanner=Marshal.AllocHGlobal(832);
        Marshal.Copy(new byte[832],0,collided,832);Marshal.Copy(new byte[832],0,scanner,832);
        Marshal.WriteInt32(collided,unchecked((int)0x11112222));
        Marshal.WriteInt32(scanner,unchecked((int)0x33334444));
        Marshal.WriteIntPtr(context,156,collided);Marshal.WriteIntPtr(context,164,scanner);
        Marshal.WriteIntPtr(pointers,0,record);Marshal.WriteIntPtr(pointers,4,context);
        Callback invoke=(Callback)Marshal.GetDelegateForFunctionPointer(At(allocation,0x5900),typeof(Callback));
        if (invoke(pointers)!=0) throw new Exception("Observer changed exception handling");
        byte[] observed=File.ReadAllBytes(destination);
        if (observed.Length!=3484 || BitConverter.ToUInt32(observed,0)!=0xc0000005 || BitConverter.ToUInt32(observed,80+184)!=0xdeadbeef || BitConverter.ToUInt32(observed,796)!=0x12345678 || BitConverter.ToUInt32(observed,1820)!=0x11112222 || BitConverter.ToUInt32(observed,2652)!=0x33334444) throw new Exception("Native exception record mismatch");
    }
}
'@
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'port-audit/test-supplies/native-patch.json') -Raw | ConvertFrom-Json
$hex = ($manifest.patches | Where-Object { $_.address -eq 0xb95900 }).after
$code = New-Object byte[] ($hex.Length/2)
for ($i=0; $i -lt $code.Length; $i++) { $code[$i]=[Convert]::ToByte($hex.Substring($i*2,2),16) }
$destination=Join-Path $PSScriptRoot ('logs/native-recorder-selftest-'+[Guid]::NewGuid().ToString('N')+'.bin')
[RecorderSelfTest]::Run($code,$destination)
Write-Output 'Native 32-bit exception callback: PASS (record, context, return value verified)'
