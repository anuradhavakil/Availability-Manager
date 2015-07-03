/**
 * @author Anuradha S Vakil
 * @Since 2014
 * @References VMWare Code samples, VIJAVA API, VMWARE API DOCUMENTATION
 * HighAvailability Manager Class for Project 1 - Cmpe 283
 */
package com.vmware.vim25.mo.samples;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

import com.vmware.vim25.mo.Folder;
import com.vmware.vim25.mo.InventoryNavigator;
import com.vmware.vim25.mo.ManagedEntity;
import com.vmware.vim25.mo.ServiceInstance;
import com.vmware.vim25.mo.Task;
import com.vmware.vim25.mo.VirtualMachine;
import com.vmware.vim25.mo.VirtualMachineSnapshot;
import com.vmware.vim25.mo.samples.vm.MigrateVM;
import com.vmware.vim25.mo.samples.vm.VMSnapshot;

public class HighAvailabilityManager 
{
	String urlStr;
	String username;
	String password;
	String alarmName;
	VirtualMachine vm;
	ServiceInstance si;


	/*
	 * Invoke high availability manager thread
	 */
	public static void main(String[] args) throws Exception {
		HighAvailabilityManager program = new HighAvailabilityManager();
		program.new VMManager().start();
	}

	//initialize vm in constructor
	public HighAvailabilityManager() throws Exception {
		urlStr =  "https://130.65.132.214/sdk";
		username = "root";
		password = "12!@qwQW";
		alarmName = "MyPowerStateAlarm";

		URL url = new URL(urlStr);
		long start = System.currentTimeMillis();
		si = new ServiceInstance(url, username , password, true);
		long end = System.currentTimeMillis();
		System.out.println("time taken:" + (end-start));
		vm = initializeVM(si);
		displayStatisticsData(vm);

	}

	/*
	 * VMManager Thread responsible for checking VM state,
	 * invoking another thread for taking snapshot
	 * revert to another snapshot if VM is in bad state
	 * trigger alarm if VM is manually powered off
	 */
	public class VMManager extends Thread {

		@Override
		public void run(){
			int counter = 0;
			try {

				while(true){
					displayStatisticsData(vm);
					if (! ping("130.65.132.214")) { //vhost is not reachable 

						MigrateVM.migrateVM(urlStr, username, password, "130.65.132.219");

					} else if((vm.getGuest().getIpAddress()) != null && (counter % 10 == 0)) { 

						// vm is up in good state to take snapshot and last snapshot was before 10 mins. Invoke a new thread
						new VMSnapshotGenerator().start();

					} else if((vm.getGuest().getIpAddress() == null) && (!vm.getRuntime().getPowerState().toString().equalsIgnoreCase("poweredoff"))) {

						System.out.println("Restoring VM from Snapshot ...");
						try {

							restoreFromSnapshot(vm, si.getRootFolder());

						} catch (Exception e) {

							System.out.println("Exception Restoring VM from Snapshot ...");
							e.printStackTrace();
						}

					} else { // vm is turned off and hence trigger alarm

						if(vm.getRuntime().getPowerState().toString().equalsIgnoreCase("poweredoff")){

							System.out.println("Triggering Alaram since VM is Powere Off");
							VMPowerStateAlarm.triggerVmStateAlarm(urlStr, username, password, vm.getName(), alarmName);
						}

					}

					Thread.sleep(1000 * 60 * 1);
					counter ++;

				}

			} catch (Exception e) {
				System.out.println("Exception in VMManager thread");
				e.printStackTrace();
			}

		}

	}

	/*
	 * Light weight thread that takes the snapshot 
	 * when invoked by VMManager
	 */
	public class VMSnapshotGenerator extends Thread {

		@Override
		public void run(){

			String snapshotname = "backup_vm";

			try {

				Task task = vm.removeAllSnapshots_Task();

				if(task.waitForMe()==Task.SUCCESS) {
					System.out.println("Successfully removed snapshot " + snapshotname + " on " + vm.getName());
				} else {
					throw new Exception("Error creating snapshot!");
				}

				task = vm.createSnapshot_Task(snapshotname, "auto", true, true);

				if(task.waitForMe()==Task.SUCCESS) {

					System.out.println("Successfully created snapshot " + snapshotname + " on " + vm.getName());
				} else {
					throw new Exception("Error creating snapshot!");
				}

			} catch (Exception e){
				System.out.println("Exception in Create Snapshot task");
			}

		}

	}

	public void restoreFromSnapshot (VirtualMachine vm, Folder rootFolder )  throws Exception{


		VirtualMachine vmSrc = (VirtualMachine) new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine", "Team09_Ubuntu_Nachu");
		VirtualMachineSnapshot vmsnap = VMSnapshot.getSnapshotInTree(vmSrc, "backup_vm");

		if(vmsnap!=null) {
			Task task = vmsnap.revertToSnapshot_Task(null);
			if(task.waitForMe()==Task.SUCCESS) {
				System.out.println("Reverted to snapshot:" );
			}
		}
	}

	public void displayStatisticsData(VirtualMachine vm) throws Exception{

		vm.getResourcePool();
		System.out.println("\n --------------------------------------- \n" );
		System.out.println("Hello " + vm.getName());
		System.out.println("GuestOS: " + vm.getConfig().getGuestFullName());
		System.out.println("Multiple snapshot supported: " + vm.getCapability().isMultipleSnapshotsSupported());

		System.out.println("Vm Memory: " + vm.getSummary().quickStats.getHostMemoryUsage() + " MB");
		System.out.println("Overall CPU Usage: " + vm.getSummary().quickStats.getOverallCpuUsage() + " MHz");
		System.out.println("IP address: " + vm.getGuest().getIpAddress() );
		System.out.println("VM Overall Status: "+ vm.getOverallStatus().toString() );
		System.out.println("Network: " + vm.getNetworks()[0].getName());
		System.out.println("VM is: " + vm.getGuest().getGuestState());
		System.out.println("VM Power state: " + vm.getRuntime().getPowerState().toString());
		System.out.println(vm.getGuest().getNet());
		System.out.println("\n --------------------------------------- \n" );

	}

	public VirtualMachine initializeVM(ServiceInstance si) throws Exception {

		Folder rootFolder = si.getRootFolder();
		String name = rootFolder.getName();
		System.out.println("root:" + name);
		ManagedEntity[] mes = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine");
		if(mes==null || mes.length ==0)
		{
			System.out.println("No VMs found for the host");
			return null;
		}

		return  (VirtualMachine) mes[0];
	}

	/*
	 * Ping Util method to ping VHOST or VM
	 * 
	 */
	public Boolean  ping( String host ) throws Exception {

		String cmd = "ping " + host;
		int maxReadLines = 0;
		Runtime rt = Runtime.getRuntime();
		Process p = rt.exec(cmd);
		System.out.println("Pinging VHost: "+ host );
		String result="";

		BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));

		while(in.readLine() != null && maxReadLines < 6) {
			result = result + in.readLine();
			System.out.println(result);
			maxReadLines ++;
		}
		in.close();
		p.destroy();

		return result.contains("Unreachable") ? false : true;
	}

}