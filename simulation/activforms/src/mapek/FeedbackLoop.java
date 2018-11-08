package mapek;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import deltaiot.client.Effector;
import deltaiot.services.LinkSettings;
import deltaiot.services.QoS;
import smc.SMCConnector;
import util.ConfigLoader;
import deltaiot.client.Probe;

public class FeedbackLoop {

	//The probe and effector of the network being worked on.
	Probe probe;
	Effector effector;

	// Knowledge

	//TODO: distribution gap aanpassen om grotere space te creeeren, is enige mogelijkeid.
	public static final int DISTRIBUTION_GAP = ConfigLoader.getInstance().getDistributionGap();
	
	//Dit zullen volledige staten(=configuratie) zijn van het netwerk op een bepaald moment
	Configuration currentConfiguration;
	Configuration previousConfiguration;

	// TODO: Geen idee
	// Als ik de class bekijk lijkt het alsof steps(i)
	// de aanpassing is die je deed op het einde van cycle i+1
	// aan de power en distribution
	// Er moet echter verder inde code gekeken worden

	// The above seems wrong.
	// As far as I can tell, this gets filled by the planner each cycle
	// and probably emptied by the executor after executing the step (changes) the planner planned.
	List<PlanningStep> steps = new LinkedList<>();
	
	//Stellen de ruis op een bepaalde link voor (op een bepaald tijdstip?)
	//Zijn ergens manueel ingetypt en komt van gemeten data van een week van het echte netwerk
	List<SNREquation> snrEquations = new LinkedList<>();

	// Adaption space
	List<AdaptationOption> adaptationOptions = new LinkedList<>();
	
	// TODO: geen idee
	List<AdaptationOption> verifiedOptions;

	// De connector die met de machine learner connecteerd.
	SMCConnector smcConnector = new SMCConnector();

	/*
	* Thressholds for when you want to addapt/change the network
	*/

	//SNR of the links
	static final int SNR_BELOW_THRESHOLD = 0;
	static final int SNR_UPPER_THRESHOLD = 5;

	// (QoS) Thresshold to high energy comsumption motes
	static final int ENERGY_CONSUMPTION_THRESHOLD = 5;

	// (QoS) when packet loss too high
	static final int PACKET_LOSS_THRESHOLD = 5;

	// Gets triggered when the difference between the last two cycles is greater then this,
	// but also when it is smaller then minus this
	static final int MOTES_TRAFFIC_THRESHOLD = 10;

	// TODO: Hardcoded number of motes and links in the network.
	// WIll cause problems when simulating the other network.
	static final int MAX_LINKS = 17;
	static final int MAX_MOTES = 15;



	// Gets called from the main.
	public FeedbackLoop() {
		//TODO: leg me uit hoe die lamda werkt aub en waar de SNR equitions worden toegevoegd
	}

	//Sets the probe of the feedbackloop
	public void setProbe(Probe probe) {
		this.probe = probe;
	}

	//Sets the effector of the feedbackloop
	public void setEffector(Effector effector) {
		this.effector = effector;
	}

	public void setEquations(List<SNREquation> equations) {
		snrEquations = equations;
	}

	//This is were the feedback loop really starts.
	public void start() {
		System.out.println("Feedback loop started.");

		// Run the mape-k loop and simulator for the specified amount of cycles
		for (int i = 1; i <= ConfigLoader.getInstance().getAmountOfCycles(); i++) {
			System.out.print(i + ";" + System.currentTimeMillis());
			
			// Start the monitor part of the mapek loop
			// The rest of the parts are each called in the previous parts
			monitor();
		}
	}


	void monitor() {
		// The method "probe.getAllMotes()" also makes sure the simulator is run for a single cycle
		ArrayList<deltaiot.services.Mote> motes = probe.getAllMotes();
		
		
		List<Mote> newMotes = new LinkedList<>();

		// configuratie/cyclus/state opschuiven
		// CurrentCon.. is initialised as null.
		// So prevConf will be null on the first cycle
		// TODO: it isnt! Maybe Java does it for us.
		previousConfiguration = currentConfiguration;

		// Init new configuration
		currentConfiguration = new Configuration();

		// Maakt copy van netwerk in huidige staat

		// Makes copy of the IoT network in its current state
		Mote newMote;
		Link newLink;

		// Iterate through all the motes OF THE SIMULATOR
		for (deltaiot.services.Mote mote : motes) {

			// Make a new mote and give it the ID of the mote being iterated on
			newMote = new Mote();
			newMote.moteId = mote.getMoteid();

			// Adds the current battery level to the mote
			newMote.energyLevel = mote.getBattery();

			// The motesLoad is a list with the load of the motes.
			// I think every element of that list represents the load
			// on the mote in a cycle in the past.
			// Here you add a new load to the list for the current cycle
			// TODO: the way the load is calculated should be looked at.
			// TODO: the dataprobability is a constant? how can that be the load?
			currentConfiguration.environment.motesLoad
					.add(new TrafficProbability(mote.getMoteid(), mote.getDataProbability()));
			
			
			// Copy the links and its SNR
			//TODO: so the SNR and load never change for a mote throughout time?
			for (deltaiot.services.Link link : mote.getLinks()) {
				newLink = new Link();
				newLink.source = link.getSource();
				newLink.destination = link.getDest();
				newLink.distribution = link.getDistribution();
				newLink.power = link.getPower();
				newMote.links.add(newLink);
				currentConfiguration.environment.linksSNR.add(new SNR(link.getSource(), link.getDest(), link.getSNR()));
			}
			
			// add the mote to the configuration
			newMotes.add(newMote);
		}

		// This saves the architecture of the system to the new configuration by adding the 
		// new motes which contain all the necessary data
		currentConfiguration.system = new ManagedSystem(newMotes);
		
		//getNetworkQoS(n) returns a list of the QoS
		// values of the n previous cycles.
		//This returns the latest QoS and
		// returns the first (and only) element of the list.
		QoS qos = probe.getNetworkQoS(1).get(0);

		//Hier neemt hij enkel deze 2 mee TODO
		// Zie of je de latency enzo ook kunt meegeven
		// Adds the QoS of the previous configuration to the current configuration,
		// probably to pass on to the learner so he can use this to online learn
		// TODO: modify this to multiple goals
		currentConfiguration.qualities.packetLoss = qos.getPacketLoss();
		currentConfiguration.qualities.energyConsumption = qos.getEnergyConsumption();

		// Call the next step off the mapek
		analysis();
	}





	// Gets called at the end of the monitor method
	void analysis() {

		// analyze all link settings
		// returns false if no change has to be made.
		// 
		// Otherwise it returns true, to see when you should look at the definition below
		// The tressholds are stated in the beginning of the class.
		boolean adaptationRequired = analysisRequired();

		// Return when no adaption is needed
		// this returns you to the end of the monitor function which return to a new cycle
		// It stops from doing the other steps
		if (!adaptationRequired)
			return;

		// creates a new object for the adaption option
		AdaptationOption newPowerSettingsConfig = new AdaptationOption();
		
		// copy the system = architecture of the network = managed system.java
		newPowerSettingsConfig.system = currentConfiguration.system.getCopy();

		// I think this adapts the power until the SNR reaches zero
		analyzePowerSettings(newPowerSettingsConfig);

		// when there are 2 outgoing links and they are both 100, set one to 0
		// Seems weird, why do this. Its dirty programming, but what is the origin of the problem.
		removePacketDuplication(newPowerSettingsConfig);

		// See the function below
		// This adds the possible link distributions to the motes who have 2 outgoing links
		composeAdaptationOptions(newPowerSettingsConfig);

		//TODO: this todo is to show this is very important
		// You pass the adaptionOptions and the environment (noise and load) to the connector
		smcConnector.setAdaptationOptions(adaptationOptions, currentConfiguration.environment);

		// let the model checker and/or machine learner start to predict which adaption options will
		// fullfill the goals definied in the connector
		smcConnector.startVerification();

		// the connector changed the adaptionOptions of the feedbackloop directly,
		// to the options it thinks will suffiece the goals
		// verifiedOptions is also an argument of the feedbackloop object
		// and should require a setter...
		verifiedOptions = adaptationOptions;

		// Continue to the planning step.
		planning();
	}


	// This should be done in the init or something...
	// TODO: hardcoded for 2 parents
	// Not good if the next network has more then 2.
	void composeAdaptationOptions(AdaptationOption newConfiguration) {

		// init new list of motes
		List<Mote> moteOptions = new LinkedList<>();
		
		
		// This adaptionOptions are the ones of the feedbackloop that is directly accessed
		// TODO: make and use a getter for this, its very unclear like this 
		// is this only for the first adaption or also the second?
		if (adaptationOptions.size() <= 1) 
		{
			// adaptationOptions.add(newConfiguration);
			// generate adaptation options for the first time
			int initialValue = 0;

			// For all motes in the system/network
			for (Mote mote : newConfiguration.system.motes.values()) {

				// If two links
				// TODO: hardcoded
				if (mote.getLinks().size() == 2) {
					
					//make a copy of the mote and make the moteoptions empty
					mote = mote.getCopy();
					moteOptions.clear();

					// Add the different districution options for 2 parent links
					for (int i = initialValue; i <= 100; i += DISTRIBUTION_GAP) {
						mote.getLink(0).setDistribution(i);
						mote.getLink(1).setDistribution(100 - i);
						moteOptions.add(mote.getCopy());
					}

					initialValue = 20;

					// add the new option to the global (feedbackloop object) adaption options for the mote
					saveAdaptationOptions(newConfiguration, moteOptions, mote.getMoteId());
				}
			}
		}
	}

	private void saveAdaptationOptions(AdaptationOption firstConfiguration, List<Mote> moteOptions, int moteId) {
		AdaptationOption newAdaptationOption;

		// If feedbackloops adaptions options are empty
		if (adaptationOptions.isEmpty()) {

			// for the new options, add them to the global options
			for (int j = 0; j < moteOptions.size(); j++) {
				newAdaptationOption = firstConfiguration.getCopy();
				newAdaptationOption.system.motes.put(moteId, moteOptions.get(j));

				// here your add them
				adaptationOptions.add(newAdaptationOption);
			}

		// if there are already addaption options
		} else {
			
			int size = adaptationOptions.size();
			
			//for all adaption options
			for (int i = 0; i < size; i++) {
			
				//for the new moteOptions
				for (int j = 0; j < moteOptions.size(); j++) {
					newAdaptationOption = adaptationOptions.get(i).getCopy();
					newAdaptationOption.system.motes.put(moteId, moteOptions.get(j));
					adaptationOptions.add(newAdaptationOption);
				}
			}
		}
	}

	// Gets called to make a new adaption in analyse()
	private void analyzePowerSettings(AdaptationOption newConfiguration) {


		int powerSetting;
		double newSNR;

		// Iterate over the motes of the managed system (values returns a list or array with the motes)
		for (Mote mote : newConfiguration.system.motes.values()) {

			// iterate over the links of the motes
			// TODO: are these outgoing and ingoing or one of the two?
			// I think it are only the outgoing links
			for (Link link : mote.getLinks()) {

				// Get the link power
				powerSetting = link.getPower();

				// get the SNR of the link
				newSNR = currentConfiguration.environment.getSNR(link);

				// TODO: what does the followoing?

				// find interference
				double diffSNR = getSNR(link.getSource(), link.getDestination(), powerSetting) - newSNR;

				if (powerSetting < 15 & newSNR < 0 && newSNR != -50) {

					while (powerSetting < 15 && newSNR < 0) {
						newSNR = getSNR(link.getSource(), link.getDestination(), ++powerSetting) - diffSNR;
					}

				}
				
				else if (newSNR > 0 && powerSetting > 0) 
				{
					do {

						newSNR = getSNR(link.getSource(), link.getDestination(), powerSetting - 1) - diffSNR;

						if (newSNR >= 0) {
							powerSetting--;
						}

					} while (powerSetting > 0 && newSNR >= 0);
				}

				if (link.getPower() != powerSetting) {

					link.setPower(powerSetting);

					currentConfiguration.environment.setSNR(link,
							getSNR(link.getSource(), link.getDestination(), powerSetting) - diffSNR);
				}
			}
		}
	}

	// when there are 2 outgoing links and they are both 100, set one to 0
	private void removePacketDuplication(AdaptationOption newConfiguration) {
		for (Mote mote : newConfiguration.system.motes.values()) {
			//TODO: hardcoded only 2 paretns, what if 3?
			if (mote.getLinks().size() == 2) {
				if (mote.getLink(0).getDistribution() == 100 && mote.getLink(1).getDistribution() == 100) {
					mote.getLink(0).setDistribution(0);
					mote.getLink(1).setDistribution(100);
				}
			}
		}
	}

	double getSNR(int source, int destination, int newPowerSetting) {
		for (SNREquation equation : snrEquations) {
			if (equation.source == source && equation.destination == destination) {
				return equation.multiplier * newPowerSetting + equation.constant;
			}
		}
		throw new RuntimeException("Link not found:" + source + "-->" + destination);
	}

	// int i;
	boolean analysisRequired() {
		// for simulation we use adaptation after 4 periods
		// return i++%4 == 0;

		// if first time perform adaptation
		if (previousConfiguration == null)
			return true;

		// Check LinksSNR
		double linksSNR;
		for (int j = 0; j < MAX_LINKS; j++) {
			linksSNR = currentConfiguration.environment.linksSNR.get(j).SNR;
			if (linksSNR < SNR_BELOW_THRESHOLD || linksSNR > SNR_UPPER_THRESHOLD) {
				return true;
			}
		}

		// Check MotesTraffic
		double diff;

		for (int i = 2; i <= MAX_MOTES; i++) {
			diff = currentConfiguration.environment.motesLoad.get(i).load
					- previousConfiguration.environment.motesLoad.get(i).load;
			if (diff > MOTES_TRAFFIC_THRESHOLD || diff > -MOTES_TRAFFIC_THRESHOLD) {
				return true;
			}
		}

		// check qualities
		if ((currentConfiguration.qualities.packetLoss > previousConfiguration.qualities.packetLoss
				+ PACKET_LOSS_THRESHOLD)
				|| (currentConfiguration.qualities.energyConsumption > previousConfiguration.qualities.energyConsumption
						+ ENERGY_CONSUMPTION_THRESHOLD)) {
			return true;
		}

		// check if system settings are not what should be
		return !currentConfiguration.system.toString().equals(previousConfiguration.system.toString());

	}


	// The planning step of the mape loop
	// Selects "the best" addaption options of the predicted/ verified ones 
	// and plans the option to be executed
	// it assumes some options have been send, so could be dangerous but I think not
	void planning() {

		// init an adaption option
		AdaptationOption bestAdaptationOption = null;

		// For all options the smc and ml thought they would fullfill the goals
		//TODO: here he selects the best option, has to be changed to my goals
		for (int i = 0; i < verifiedOptions.size(); i++) {

			//TODO: important changes have to be done here for more goals
			
			// if the option satisfies the hardcoded packetloss goal, and
			// the energy consumption is the best seen yet, change this to the 
			// "best"option
			if (Goals.satisfyGoalPacketLoss(verifiedOptions.get(i))
					&& Goals.optimizationGoalEnergyCosnumption(bestAdaptationOption, verifiedOptions.get(i))) {

				bestAdaptationOption = verifiedOptions.get(i);
			}
		}


		// if none of the verified options fullfilled the goals
		if (bestAdaptationOption == null) {
			// System.out.println("Using faile safety configuration");

			// TODO: hardcoded

			// If none is predicted to fullfill the goal, just take the one with the lowest energy consumption
			// Bad. This should can be done in a smarter way, but there is no time.
			for (int i = 0; i < verifiedOptions.size(); i++) {
				if (Goals.optimizationGoalEnergyCosnumption(bestAdaptationOption, verifiedOptions.get(i))) {
					bestAdaptationOption = verifiedOptions.get(i);
				}
			}
		}
		// System.out.print(";" + bestAdaptationOption.verificationResults.packetLoss +
		// ";"
		// + bestAdaptationOption.verificationResults.energyConsumption);
		// System.out.println("SelectedOption:" + bestAdaptationOption);

		// Go through all links
		Link newLink, oldLink;
		for (Mote mote : bestAdaptationOption.system.motes.values()) {

			// for all links
			for (int i = 0; i < mote.getLinks().size(); i++) {


				// predicted mote, which will be executed
				newLink = mote.getLinks().get(i);

				// get the current link configuration. which will become the old one
				oldLink = currentConfiguration.system.motes.get(mote.moteId).getLink(i);

				// If the power isnt equal, aka changed
				if (newLink.getPower() != oldLink.getPower()) {

					// add a step/change to be executed later
					steps.add(new PlanningStep(Step.CHANGE_POWER, newLink, newLink.getPower()));
				}

				// if the distribution should change
				if (newLink.getDistribution() != oldLink.getDistribution()) {

					// add a step/change to be executed later
					steps.add(new PlanningStep(Step.CHANGE_DIST, newLink, newLink.getDistribution()));
				}
			}
		}

		// if there are steps to be executed, trigger execute to do them
		if (steps.size() > 0) {
			execution();
		} 
		
		// if you wont change anything, just print je current time 
		// to be able to know how long the previous took
		else {
			System.out.println(";" + System.currentTimeMillis());
		}
	}


	// gets called if there was a thresshold passed to look at new adaption,
	// and the  new adaption chosen differs from the previous one, 
	// so changes/steps have to be done
	void execution() {

		// init boolean
		boolean addMote;

		// init list of motes
		List<Mote> motesEffected = new LinkedList<Mote>();

		// for all motes in the current (to become old) configuration.
		// aka for all motes in the system (system doesnt change)
		for (Mote mote : currentConfiguration.system.motes.values()) {

			// default do not add mote
			addMote = false;

			// for all planning steps/changes planned by the planner 
			for (PlanningStep step : steps) {

				// if the mote is the source of a link which will change (its options)
				// add the mote
				if (step.link.getSource() == mote.getMoteId()) {
					addMote = true;

					// if this step is to change to power
					// find the link object of the mote from the mote to the destinitaion of the link,
					// and change its power to the planned value
					if (step.step == Step.CHANGE_POWER) {
						findLink(mote, (step.link.getDestination())).setPower(step.value);
					} 
					
					// if this step is to change to distribution
					// find the link object of the mote from the mote to the destinitaion of the link,
					// and change its distribution to the planned value
					else if (step.step == Step.CHANGE_DIST) {
						findLink(mote, (step.link.getDestination())).setDistribution(step.value);
					}
				}
			}

			// if the mote's settings were changed, add it to effected/changed motes
			if (addMote)
				motesEffected.add(mote);
		}

		// init linksettings list, this is a deltaIoT class to representing the setting of a link
		List<LinkSettings> newSettings;

		// System.out.println("Adaptations:");

		// for all motes affected
		for (Mote mote : motesEffected) {

			// printMote(mote);

			// init the list of settings
			newSettings = new LinkedList<LinkSettings>();

			// for the (outgoing?) links of the mote
			for (Link link : mote.getLinks()) {

				// add a new linksettings object containing the source mote id, the dest id, the (new) power of the link,
				//  the (new) distribution of the link and the link spreading as zero to the newsetting list.
				//TODO: what is the spreadingsfactor and can it be used as a feature?
				newSettings.add(newLinkSettings(mote.getMoteId(), link.getDestination(), link.getPower(),
						link.getDistribution(), 0));
			}

			//TODO: very important command
			// Here you push the changes for the mote to the actual network via the effector
			effector.setMoteSettings(mote.getMoteId(), newSettings);
		}

		// empty the steps list
		steps.clear();

		// print current time, to be able to tell later how long everything took
		System.out.print(";" + System.currentTimeMillis() + "\n");
	}


	// return the link from mote to dest
	Link findLink(Mote mote, int dest) {
		for (Link link : mote.getLinks()) {
			if (link.getDestination() == dest)
				return link;
		}
		throw new RuntimeException(String.format("Link %d --> %d not found", mote.getMoteId(), dest));
	}


	// returns a link settings object with the given parameters as arguments.
	public LinkSettings newLinkSettings(int src, int dest, int power, int distribution, int sf) {
		LinkSettings settings = new LinkSettings();
		settings.setSrc(src);
		settings.setDest(dest);
		settings.setPowerSettings(power);
		settings.setDistributionFactor(distribution);
		settings.setSpreadingFactor(sf);
		return settings;
	}

	// dont know where this get used
	void printMote(Mote mote) {
		System.out.println(String.format("MoteId: %d, BatteryRemaining: %f, Links:%s", mote.getMoteId(),
				mote.getEnergyLevel(), getLinkString(mote.getLinks())));
	}

	
	// dont know where this gets used
	String getLinkString(List<Link> links) {
		StringBuilder strBuilder = new StringBuilder();
		for (Link link : links) {
			strBuilder.append(String.format("[Dest: %d, Power:%d, DistributionFactor:%d]", link.getDestination(),
					link.getPower(), link.getDistribution()));
		}
		return strBuilder.toString();
	}
}
