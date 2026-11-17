$ErrorActionPreference='Stop'
if ([IntPtr]::Size -ne 4) { throw 'Run with 32-bit Windows PowerShell.' }
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class LoginTabsTest {
 [DllImport("kernel32.dll")] static extern IntPtr VirtualAlloc(IntPtr p,UIntPtr n,uint type,uint protection);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Runner(IntPtr stage);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int NoArg();
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate IntPtr NoPtr();
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int OneInt(int n);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate IntPtr OnePtr(IntPtr p);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate IntPtr TwoPtr(IntPtr a,IntPtr b);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int PtrInt(IntPtr a);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int TwoIntPtr(IntPtr a,IntPtr b);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate IntPtr Resource(IntPtr name,int a,int b,int c);
 [UnmanagedFunctionPointer(CallingConvention.StdCall)] delegate int Draw(int x,int y,IntPtr image,int state);
 static int Down,MouseX,MouseY,Width=800,Height=600,ForegroundPid=5000;
 static List<Delegate> Keep=new List<Delegate>();
 static Dictionary<string,IntPtr> Api=new Dictionary<string,IntPtr>();
 static List<string> Images=new List<string>();
 static IntPtr Memory,Stage; static int Cases;
 static IntPtr At(int n){return new IntPtr(Memory.ToInt32()+n);}
 static IntPtr Add(string name,Delegate d){Keep.Add(d);IntPtr p=Marshal.GetFunctionPointerForDelegate(d);Api[name]=p;return p;}
 static byte[] Hex(string s){byte[] b=new byte[s.Length/2];for(int i=0;i<b.Length;i++)b[i]=Convert.ToByte(s.Substring(i*2,2),16);return b;}
 static void Patch(byte[] b,int o,int value){Array.Copy(BitConverter.GetBytes(value),0,b,o,4);}
 static int Mode(int player){return Marshal.ReadInt32(At(0x1000+player*4));}
 static void Execute(Runner run){Images.Clear();int v=run(Stage);if(v!=123456)throw new Exception("Native return value corrupted");}
 static void Click(Runner run,int x,int y){Down=0;Execute(run);MouseX=x;MouseY=y;Down=0x8000;Execute(run);}
 static void Check(bool condition,string label){if(!condition)throw new Exception(label);Cases++;}
 public static void Run(string hex,int originalBase,int[] absolute,int[] relOffsets,int[] relEnds,int[] relTargets,int[] iatOffsets,string[] iatNames){
  Memory=VirtualAlloc(IntPtr.Zero,(UIntPtr)0x5000,0x3000,0x40);if(Memory==IntPtr.Zero)throw new Exception("Allocation failed");
  Stage=Marshal.AllocHGlobal(0x300);Marshal.Copy(new byte[0x300],0,Stage,0x300);
  IntPtr wndPid=Add("windowPid",new TwoIntPtr((h,p)=>{Marshal.WriteInt32(p,ForegroundPid);return 99;}));
  Add("GetAsyncKeyState",new OneInt(n=>Down));
  Add("GetForegroundWindow",new NoPtr(()=>new IntPtr(0x123)));
  Add("GetCurrentProcessId",new NoArg(()=>5000));
  Add("GetModuleHandleA",new OnePtr(n=>new IntPtr(0x456)));
  Add("GetProcAddress",new TwoPtr((h,n)=>wndPid));
  Add("GetCursorPos",new PtrInt(p=>{Marshal.WriteInt32(p,MouseX);Marshal.WriteInt32(p,4,MouseY);return 1;}));
  Add("ScreenToClient",new TwoIntPtr((h,p)=>1));
  Add("GetClientRect",new TwoIntPtr((h,p)=>{Marshal.WriteInt32(p,0);Marshal.WriteInt32(p,4,0);Marshal.WriteInt32(p,8,Width);Marshal.WriteInt32(p,12,Height);return 1;}));
  IntPtr manager=Add("manager",new NoPtr(()=>new IntPtr(0x456)));
  IntPtr resource=Add("resource",new Resource((name,a,b,c)=>name));
  IntPtr draw=Add("draw",new Draw((x,y,image,state)=>{
   string name=image.ToInt32()==0x1234?"original":Marshal.PtrToStringAnsi(image);
   Images.Add(name+":"+x+","+y+":"+state);return image.ToInt32()==0x1234?123456:42;
  }));
  byte[] code=Hex(hex);
  foreach(int o in absolute)Patch(code,o,Memory.ToInt32()+BitConverter.ToInt32(code,o)-originalBase);
  for(int i=0;i<relOffsets.Length;i++){
   IntPtr fn=relTargets[i]==0x75e110?draw:relTargets[i]==0x75e980?manager:relTargets[i]==0x75ec60?resource:IntPtr.Zero;
   if(fn==IntPtr.Zero)throw new Exception("Unexpected native call");
   Patch(code,relOffsets[i],unchecked(fn.ToInt32()-(Memory.ToInt32()+relEnds[i])));
  }
  for(int i=0;i<iatOffsets.Length;i++){
   IntPtr slot=At(0x2000+i*4);Marshal.WriteIntPtr(slot,Api[iatNames[i]]);Patch(code,iatOffsets[i],slot.ToInt32());
  }
  Marshal.Copy(code,0,Memory,code.Length);
  byte[] wrapper=Hex("558bec56578b7508bf220200008bcf6a006834120000688901000068ac000000e8000000005f5e5dc20400");
  // CALL starts at wrapper+32, operand at33, return at37.
  Patch(wrapper,33,Memory.ToInt32()-(At(0x3000).ToInt32()+37));
  Marshal.Copy(wrapper,0,At(0x3000),wrapper.Length);
  Runner run=(Runner)Marshal.GetDelegateForFunctionPointer(At(0x3000),typeof(Runner));
  Execute(run);Check(Mode(0)==0 && Images.Count==3,"Default single-player tabs");
  Check(Images.Contains("LoginStage/LO_BtBNBId1P:277,426:1"),"Default selected game account");
  Click(run,420,435);Check(Mode(0)==1,"Select passport");
  Check(Images.Contains("LoginStage/LO_LoginBox1P:277,455:0"),"Passport green panel");
  Check(Images.Contains("LoginStage/LO_BtPTId1P:400,426:1"),"Selected passport frame");
  MouseX=300;Execute(run);Check(Mode(0)==1,"Holding mouse must not select another tab");
  Click(run,300,435);Check(Mode(0)==0 && Images.Count==3,"Switch back to game account");
  ForegroundPid=6000;Click(run,420,435);Check(Mode(0)==0,"Ignore other applications");ForegroundPid=5000;
  Width=0;Click(run,420,435);Check(Mode(0)==0,"Reject empty viewport");Width=800;
  foreach(int y in new int[]{425,457}){Click(run,420,y);Check(Mode(0)==0,"Vertical boundary");}
  Click(run,523,435);Check(Mode(0)==0,"Single-player right edge is excluded");
  Click(run,400,426);Check(Mode(0)==1,"Single-player passport top-left edge");
  Marshal.WriteInt32(Stage,0x170,1);
  Click(run,520,435);Check(Mode(1)==1 && Mode(0)==1,"Independent player-two passport");
  Check(Images.Count==7,"Both two-player green panels and tabs");
  Check(Images.Contains("LoginStage/LO_LoginBox2P:402,455:0"),"Player-two panel location");
  Click(run,210,435);Check(Mode(0)==0 && Mode(1)==1,"Player-one game account leaves player two unchanged");
  Click(run,410,435);Check(Mode(1)==0,"Player-two game account");
  foreach(int x in new int[]{199,398,399,400,401,600}){Click(run,x,435);Check(Mode(0)==0 && Mode(1)==0,"Two-player gaps and boundaries");}
  Width=1600;Height=1200;Click(run,1040,870);Check(Mode(1)==1,"Scaled viewport hit test");Width=800;Height=600;
  Click(run,299,426);Check(Mode(0)==1,"Player-one passport boundary");
  Marshal.WriteInt32(Stage,0x170,0);Down=0;Execute(run);Check(Mode(0)==1 && Images.Count==4,"Return to single-player preserves selection");
  Check(Marshal.ReadInt32(Stage,0x170)==0,"Adapter must not mutate native stage state");
  Console.WriteLine("PASS: {0} native assertions; real x86 adapter, mocked renderer/input, no game process touched",Cases);
 }
}
'@
$m=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'port-audit/login-tabs-adapter/manifest.json') -Raw | ConvertFrom-Json
[LoginTabsTest]::Run($m.payload,[int]$m.base,[int[]]$m.absolute,[int[]]$m.relative.offset,[int[]]$m.relative.end,[int[]]$m.relative.target,[int[]]$m.iat.offset,[string[]]$m.iat.name)
