package com.bwssystems.HABridge;

import static spark.Spark.*;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bwssystems.HABridge.devicemanagmeent.*;
import com.bwssystems.HABridge.hue.HueMulator;
import com.bwssystems.HABridge.plugins.http.HttpClientPool;
import com.bwssystems.HABridge.upnp.UpnpListener;
import com.bwssystems.HABridge.upnp.UpnpSettingsResource;
import com.bwssystems.HABridge.util.UDPDatagramSender;

public class HABridge {
	private static SystemControl theSystem;
	
	/*
	 * This program is based on the work of armzilla from this github repository:
	 * https://github.com/armzilla/amazon-echo-ha-bridge
	 * 
	 * This is the main entry point to start the amazon echo bridge.
	 * 
	 * This program is using sparkjava rest server to build all the http calls. 
	 * Sparkjava is a microframework that uses Jetty webserver module to host 
	 * its' calls. This is a very compact system than using the spring frameworks
	 * that was previously used.
	 * 
	 * There is a custom upnp listener that is started to handle discovery.
	 * 
	 * 
	 */
    public static void main(String[] args) {
        Logger log = LoggerFactory.getLogger(HABridge.class);
        DeviceResource theResources;
        HomeManager homeManager;
        HueMulator theHueMulator;
        UDPDatagramSender udpSender;
        UpnpSettingsResource theSettingResponder;
        UpnpListener theUpnpListener;
        BridgeSettings bridgeSettings;
        Version theVersion;
    	@SuppressWarnings("unused")
		HttpClientPool thePool;
		ShutdownHook shutdownHook = null;

        log.info("HA Bridge startup sequence...");
        theVersion = new Version();
        // Singleton initialization
        thePool = new HttpClientPool();

        bridgeSettings = new BridgeSettings();
    	// sparkjava config directive to set html static file location for Jetty
        while(!bridgeSettings.getBridgeControl().isStop()) {
            log.info("HA Bridge (v{}) initializing....", theVersion.getVersion() );
			bridgeSettings.buildSettings();
			if(bridgeSettings.getBridgeSecurity().isUseHttps()) {
				secure(bridgeSettings.getBridgeSecurity().getKeyfilePath(), bridgeSettings.getBridgeSecurity().getKeyfilePassword(), null, null);
				log.info("Using https for web and api calls");
			}
            bridgeSettings.getBridgeSecurity().removeTestUsers();
	        // sparkjava config directive to set ip address for the web server to listen on
	        ipAddress(bridgeSettings.getBridgeSettingsDescriptor().getWebaddress());
	        // sparkjava config directive to set port for the web server to listen on
	        port(bridgeSettings.getBridgeSettingsDescriptor().getServerPort());
	    	staticFileLocation("/public");
	    	initExceptionHandler((e) -> HABridge.theExceptionHandler(e, bridgeSettings.getBridgeSettingsDescriptor().getServerPort()));
	        if(!bridgeSettings.getBridgeControl().isReinit())
	        	init();
	        bridgeSettings.getBridgeControl().setReinit(false);
	        // setup system control api first
	        theSystem = new SystemControl(bridgeSettings, theVersion);
	        theSystem.setupServer();

			// Add shutdown hook to be able to properly stop server
			if (shutdownHook != null) {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			}
			shutdownHook = new ShutdownHook(bridgeSettings, theSystem);
			Runtime.getRuntime().addShutdownHook(shutdownHook);

	        // setup the UDP Datagram socket to be used by the HueMulator and the upnpListener
	        udpSender = UDPDatagramSender.createUDPDatagramSender(bridgeSettings.getBridgeSettingsDescriptor().getUpnpResponsePort());
	        if(udpSender == null) {
	        	bridgeSettings.getBridgeControl().setStop(true);	        	
	        }
	        else {
		        //Setup the device connection homes through the manager
		        homeManager = new HomeManager();
		        homeManager.buildHomes(bridgeSettings, udpSender);
		        // setup the class to handle the resource setup rest api
		        theResources = new DeviceResource(bridgeSettings, homeManager);
		        // setup the class to handle the hue emulator rest api
		        theHueMulator = new HueMulator(bridgeSettings, theResources.getDeviceRepository(), theResources.getGroupRepository(), homeManager);
		        theHueMulator.setupServer();
		        // wait for the sparkjava initialization of the rest api classes to be complete
		        awaitInitialization();

		        if(bridgeSettings.getBridgeSettingsDescriptor().isTraceupnp()) {
		        	log.info("Traceupnp: upnp config address: {} -useIface: {} on web server: {}:{}",
							bridgeSettings.getBridgeSettingsDescriptor().getUpnpConfigAddress(),
							bridgeSettings.getBridgeSettingsDescriptor().isUseupnpiface(),
							bridgeSettings.getBridgeSettingsDescriptor().getWebaddress(),
							bridgeSettings.getBridgeSettingsDescriptor().getServerPort());
				}
		        // setup the class to handle the upnp response rest api
		        theSettingResponder = new UpnpSettingsResource(bridgeSettings);
		        theSettingResponder.setupServer();

		        // start the upnp ssdp discovery listener
		        theUpnpListener = null;
		        try {
					theUpnpListener = new UpnpListener(bridgeSettings, bridgeSettings.getBridgeControl(), udpSender);
				} catch (IOException e) {
					log.error("Could not initialize UpnpListener, exiting....", e);
					theUpnpListener = null;
				}
		        if(theUpnpListener != null && theUpnpListener.startListening())
		        	log.info("HA Bridge (v{}) reinitialization requessted....", theVersion.getVersion());
		        else
		        	bridgeSettings.getBridgeControl().setStop(true);
		        if(bridgeSettings.getBridgeSettingsDescriptor().isSettingsChanged())
		        	bridgeSettings.save(bridgeSettings.getBridgeSettingsDescriptor());
		        log.info("Going to close all homes");
		        homeManager.closeHomes();
		        udpSender.closeResponseSocket();
		        udpSender = null;
	        }
	        stop();
	        if(!bridgeSettings.getBridgeControl().isStop()) {
	        	try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					log.error("Sleep error: {}", e.getMessage());
				}
	        }
        }
        bridgeSettings.getBridgeSecurity().removeTestUsers();
        if(bridgeSettings.getBridgeSecurity().isSettingsChanged())
        	bridgeSettings.updateConfigFile();
		try {
			HttpClientPool.shutdown();
		} catch (InterruptedException e) {
			log.warn("Error shutting down http pool: {}", e.getMessage());;
		} catch (IOException e) {
			log.warn("Error shutting down http pool: {}", e.getMessage());;
		}
		thePool = null;
        log.info("HA Bridge (v{}) exiting....", theVersion.getVersion());
        System.exit(0);
    }
    
    private static void theExceptionHandler(Exception e, Integer thePort) {
		Logger log = LoggerFactory.getLogger(HABridge.class);
		if(e.getMessage().equals("no valid keystore") || e.getMessage().equals("keystore password was incorrect")) {
			log.error("Https settings have been removed as {}. Restart system manually after this process exits....", e.getMessage());
			log.warn(theSystem.removeHttpsSettings());
		}
		else {
			log.error("Could not start ha-bridge webservice on port [{}] due to: {}", thePort, e.getMessage());
			log.warn(theSystem.stop());
		}
	}
}
