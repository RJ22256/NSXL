// Started by Windows as the Xbox mode home app. NSXL records where it lives in
// HKCU\Software\NSXL\ExePath each time it runs; this opens it and waits until it closes.
using System;
using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

internal static class Program
{
    [STAThread]
    static int Main()
    {
        string exe = null;
        using (var k = Registry.CurrentUser.OpenSubKey(@"Software\NSXL"))
            if (k != null) exe = k.GetValue("ExePath") as string;
        if (string.IsNullOrEmpty(exe) || !File.Exists(exe))
        {
            string guess = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "NSXL", "NSXL.exe");
            if (File.Exists(guess)) exe = guess;
        }
        if (exe == null)
        {
            System.Windows.Forms.MessageBox.Show("Open NSXL once from the Start menu, then try Xbox mode again.", "NSXL");
            return 1;
        }
        var p = Process.Start(new ProcessStartInfo(exe, "--fse") { UseShellExecute = false, WorkingDirectory = Path.GetDirectoryName(exe) });
        if (p != null) p.WaitForExit();
        return 0;
    }
}
