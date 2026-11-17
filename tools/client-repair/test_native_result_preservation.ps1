$ErrorActionPreference = 'Stop'
if ([IntPtr]::Size -ne 4) { throw 'Run in 32-bit Windows PowerShell.' }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ResultPreservationTest {
    [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p, UIntPtr n, uint type, uint protection);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int Runner(IntPtr stage);
    static IntPtr At(IntPtr p,int n) { return new IntPtr(p.ToInt32()+n); }
    static void Word(IntPtr p,int n,int v) { Marshal.WriteInt32(p,n,v); }
    static void Pointer(IntPtr p,int n,IntPtr v) { Marshal.WriteIntPtr(p,n,v); }
    static byte[] Hex(string s) {
        byte[] b=new byte[s.Length/2];
        for(int i=0;i<b.Length;i++) b[i]=Convert.ToByte(s.Substring(i*2,2),16);
        return b;
    }
    public static void Run(string hex,int[] offsets,int[] ends,int[] targets) {
        IntPtr memory=VirtualAlloc(IntPtr.Zero,(UIntPtr)0x3000,0x3000,0x40);
        IntPtr stage=Marshal.AllocHGlobal(0x5000), settings=Marshal.AllocHGlobal(0x600);
        IntPtr global=At(memory,0x1800);
        Pointer(global,0,settings);
        byte[] code=Hex(hex);
        for(int i=0;i<code.Length-4;i++) {
            if(code[i]==0xa1 && BitConverter.ToUInt32(code,i+1)==0x993c44)
                Array.Copy(BitConverter.GetBytes(global.ToInt32()),0,code,i+1,4);
        }
        for(int i=0;i<offsets.Length;i++) {
            int destination;
            if(targets[i]==0x54acba) destination=At(memory,0x800).ToInt32();
            else if(targets[i]==0x555688) destination=At(memory,0x820).ToInt32();
            else if(targets[i]==0x5585e8) destination=At(memory,0x840).ToInt32();
            else if(targets[i]==0x55864d) destination=At(memory,0x850).ToInt32();
            else throw new Exception("Unexpected external target");
            Array.Copy(BitConverter.GetBytes(destination-(memory.ToInt32()+ends[i])),0,code,offsets[i],4);
        }
        Marshal.Copy(code,0,memory,code.Length);
        // Isolated substitutes for native node clear and phase-2 assignment.
        // The real adapter's branches, splicing, memory writes and preservation
        // execute natively. No game process or game thread is touched.
        byte[] clear=Hex("8909894904c3"); Marshal.Copy(clear,0,At(memory,0x800),clear.Length);
        byte[] phase=Hex("8b44240489813c3f0000c20400"); Marshal.Copy(phase,0,At(memory,0x820),phase.Length);
        Marshal.Copy(Hex("b801000000c3"),0,At(memory,0x840),6);
        Marshal.Copy(Hex("31c0c3"),0,At(memory,0x850),3);
        byte[] wrapper=Hex("558bec568b7508e8000000005e5dc3");
        Array.Copy(BitConverter.GetBytes(memory.ToInt32()-(At(memory,0x900).ToInt32()+12)),0,wrapper,8,4);
        Marshal.Copy(wrapper,0,At(memory,0x900),wrapper.Length);
        Runner run=(Runner)Marshal.GetDelegateForFunctionPointer(At(memory,0x900),typeof(Runner));
        int cases=0;
        foreach(int mode in new int[]{14,15,16,0}) foreach(int initialPhase in new int[]{0,1,2,3}) foreach(int count in new int[]{0,1,3}) {
            Marshal.Copy(new byte[0x5000],0,stage,0x5000);
            Marshal.Copy(new byte[0x600],0,settings,0x600);
            Word(settings,0x570,mode);Word(stage,0x3f3c,initialPhase);
            Word(stage,0x30d0,7);Word(stage,0x3f40,99);
            IntPtr source=At(stage,0x3bf8), backup=At(stage,0x3f44);
            Pointer(source,0,source);Pointer(source,4,source);
            Pointer(backup,0,backup);Pointer(backup,4,backup);
            IntPtr previous=source;
            for(int i=0;i<count;i++) {
                IntPtr node=At(memory,0x1000+i*0x10);
                Pointer(previous,0,node);Pointer(node,4,previous);Word(node,8,100+i);previous=node;
            }
            Pointer(previous,0,source);Pointer(source,4,previous);
            int result=run(stage);
            bool adapted=mode==14 && initialPhase==0;
            if(result!=((adapted || initialPhase==2)?1:0)) throw new Exception("Wrong result branch");
            if(Marshal.ReadInt32(stage,0x30d0)!=7) throw new Exception("Switched the active map context");
            if(Marshal.ReadInt32(stage,0x3f3c)!=(adapted?2:initialPhase)) throw new Exception("Wrong phase");
            if(Marshal.ReadInt32(stage,0x3f40)!=(adapted?7:99)) throw new Exception("Wrong saved context");
            IntPtr head=adapted?backup:source;
            IntPtr cursor=Marshal.ReadIntPtr(head), last=head;
            for(int i=0;i<count;i++) {
                if(cursor==head || Marshal.ReadInt32(cursor,8)!=100+i || Marshal.ReadIntPtr(cursor,4)!=last)
                    throw new Exception("Lost, reordered or corrupted a native object node");
                last=cursor;cursor=Marshal.ReadIntPtr(cursor);
            }
            if(cursor!=head || Marshal.ReadIntPtr(head,4)!=last) throw new Exception("Broken circular list");
            if(adapted && (Marshal.ReadIntPtr(source)!=source || Marshal.ReadIntPtr(source,4)!=source))
                throw new Exception("Source still owns transferred nodes");
            cases++;
        }
        Console.WriteLine("PASS: {0} native cases; current context, phases, object identities, order and both list links verified",cases);
    }
}
'@
$manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'port-audit/monster-result-preservation/manifest.json') -Raw | ConvertFrom-Json
[ResultPreservationTest]::Run($manifest.stub,[int[]]$manifest.relative.offset,[int[]]$manifest.relative.end,[int[]]$manifest.relative.target)
