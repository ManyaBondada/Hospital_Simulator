import java.util.Queue; 
import java.util.LinkedList; 
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;

public class Project2 {
    // user/default assigned numbers
    public final int NUM_PATIENTS;
    public final int NUM_DOCTORS;
    public final int NUM_NURSES;

    Semaphore patientReadyToRegister = new Semaphore(0, true); // tracks if any patient thread is ready to register
    Semaphore receptionistQueueMutex = new Semaphore(1, true); // allows mutual exclusion access to queue
    Semaphore receptionistFinishedRegisteringPatient = new Semaphore(0, true); // tracks if receptionist is ready to inform nurse that patient is ready to see doctor
    Semaphore waitingQueueMutex = new Semaphore(1, true); // allows mutual exclusion access to queue
    Semaphore patientReadyForNurse = new Semaphore(0, true); // signals an available nurse to get a patient
    Semaphore doctorQueueMutex = new Semaphore(1, true); // allows mutual exclusion access to queue
    Semaphore patientWaiting = new Semaphore(0, true); // patient signals receptionist that they are sitting in waiting room and now ready for nurse
   
    Semaphore [] doctorAvailable; // creates a semaphore for each doctor by id
    Semaphore [] patientFinished; // creates a semaphore to track if each patient has finished
    Semaphore [] patientEnteredDoctorOffice; // patient array to let doctor know they have entered office
    Semaphore [] patientIsWaitingDoctorOffice; // nurse lets doctor know there is a patient waiting in their office
    Semaphore [] patientDoctorMapping;

    Queue<Integer>[] doctorQueue; // tracks the patients assigned to each doctor
    Queue<Integer> receptionistQueue = new LinkedList<Integer>(); // order in which receptionist registers each patient
    Queue<Integer> waitingQueue = new LinkedList<Integer>(); // nurse selects ready patients from this queue

    HashMap<Integer, Integer> patientDoctorMap = new HashMap<Integer,Integer>(); // maps patient to doctor's room

    ArrayList<Thread> threadList = new ArrayList<Thread>(); // maintains a list of all threads

    @SuppressWarnings("unchecked")
    Project2(int numDoctors, int numPatients) {
        NUM_PATIENTS = numPatients;
        NUM_DOCTORS = numDoctors;
        NUM_NURSES = numDoctors;

        System.out.println("Run with " 
        + NUM_PATIENTS + (NUM_PATIENTS > 1 ? " patients, " : " patient, ")            
        + NUM_NURSES + (NUM_NURSES > 1 ? " nurses, " : " nurse, ")
        + NUM_DOCTORS + (NUM_DOCTORS > 1 ? " doctors" : " doctor"));
        System.out.println();

        // initialize rest of queues/semaphores
        doctorAvailable = new Semaphore[NUM_DOCTORS];
        patientFinished = new Semaphore[NUM_PATIENTS];
        doctorQueue = new Queue[NUM_DOCTORS];
        patientIsWaitingDoctorOffice = new Semaphore[NUM_DOCTORS];
        patientEnteredDoctorOffice = new Semaphore[NUM_PATIENTS];
        patientDoctorMapping = new Semaphore[NUM_PATIENTS];
        
        for (int i = 0; i < this.NUM_DOCTORS; i++) { // create array of doctor semaphores and intialize doctor queue
            doctorAvailable[i] = new Semaphore(1, true); 
            patientIsWaitingDoctorOffice[i] = new Semaphore(0, true);
            doctorQueue[i] = new LinkedList<Integer>(); 
        }

        for (int i = 0; i < NUM_PATIENTS; i++) { // create array of patient semaphores
            patientEnteredDoctorOffice[i] = new Semaphore(0, true);
            patientDoctorMapping[i] = new Semaphore(0, true);
            patientFinished[i] = new Semaphore(0,true);
        }
        
        for (int i = 0; i < NUM_DOCTORS; i++) { // create the doctor threads
            new Doctor(i);
        }

        for (int i = 0; i < NUM_NURSES; i++) { // create the nurse threads
            new Nurse(i);
        }

        new Receptionist(); // create the receptionist thread

        for (int i = 0; i < NUM_PATIENTS; i++) { // create the patient threads
            new Patient(i);
        }
    }

    public static void main(String[] args) {
        // command line arguments
        try {
            int numDoctors = 0;
            int numPatients = 0;
    
            if (args.length == 2) { 
                numDoctors = Integer.parseInt(args[0]);
                numPatients = Integer.parseInt(args[1]);
            }
            else { 
                numDoctors = 3;
                numPatients = 3;
            }
    
            Project2 project2 = new Project2(numDoctors, numPatients);
    
            for (Thread t : project2.threadList) {
                t.join();
            }
            
            System.out.println("Simulation complete");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    class Patient implements Runnable {
        int id;

        Patient(int id) {
            this.id = id;

            Thread thread = new Thread(this);
            thread.start();
            threadList.add(thread);
        }

        public void run() {
            try { 
            System.out.println("Patient " + this.id + " enters waiting room, waits for receptionist");
            
            receptionistQueueMutex.acquire(); // add patient to receptionist queue
            receptionistQueue.add(this.id);
            receptionistQueueMutex.release();
            patientReadyToRegister.release(); // tell receptionist a patient has entered the waiting room
            
            receptionistFinishedRegisteringPatient.acquire(); // wait until receptionist has registered patient
            System.out.println("Patient " + this.id + " leaves receptionist and sits in waiting room");
            patientWaiting.release(); // tell receptionist that they are waiting in room

            patientDoctorMapping[this.id].acquire(); // wait for nurse to take patient to doctor's office
            
            doctorAvailable[patientDoctorMap.get(this.id)].acquire(); // wait for doctor to be available before entering office
            System.out.println("Patient " + this.id + " enters doctor " + patientDoctorMap.get(this.id) + "'s office");

            patientEnteredDoctorOffice[this.id].release(); // signal doctor that patient has entered their office
            
            patientFinished[this.id].acquire(); // wait until doctor says consultation is finished
            System.out.println("Patient " + this.id + " receives advice from doctor " + patientDoctorMap.get(this.id));
            System.out.println("Patient " + this.id + " leaves");
            doctorAvailable[patientDoctorMap.get(this.id)].release(); // signal doctor that patient has left and doctor is available for next patient

            } catch (InterruptedException e) {
                e.printStackTrace();
            }  
        }
    }

    class Receptionist implements Runnable {

        Receptionist() {
            Thread thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }

        public void run() {
            try {
                while (true) {
                    patientReadyToRegister.acquire(); // wait until patient has entered waiting room

                    receptionistQueueMutex.acquire(); // get patient from receptionist queue to register them
                    int id = receptionistQueue.poll(); 
                    receptionistQueueMutex.release();

                    System.out.println("Receptionist registers patient " + id);

                    waitingQueueMutex.acquire(); // add patient to waiting room queue after registering them
                    waitingQueue.add(id);
                    waitingQueueMutex.release();

                    receptionistFinishedRegisteringPatient.release(); // tell patient that they can go back to waiting room
                    patientWaiting.acquire();  // wait until patient has entered waiting room
                    patientReadyForNurse.release(); // tell nurse that there is a patient waiting for them
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    class Nurse implements Runnable {
        int id; 

        Nurse(int id) {
            this.id = id;

            Thread thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }

        public void run() {
            try {
                while (true) {
                    patientReadyForNurse.acquire(); // wait until receptionist says patient is ready
                    
                    waitingQueueMutex.acquire(); // get patient from waiting room
                    int patientId = waitingQueue.poll();
                    System.out.println("Nurse " + id + " takes patient " + patientId + " to doctor's office");
                    waitingQueueMutex.release();

                    int doctorId = this.id;
                    
                    doctorQueueMutex.acquire(); // add patient to doctor's queue
                    doctorQueue[doctorId].add(patientId);
                    doctorQueueMutex.release();
                    
                    patientIsWaitingDoctorOffice[this.id].release(); // tell doctor patient is waiting for them

                    patientDoctorMap.put(patientId, doctorId); // map patient to doctor's room
                    patientDoctorMapping[patientId].release(); 
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    class Doctor implements Runnable {
        int id;

        Doctor(int id) {
            this.id = id;

            Thread thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }

        public void run() {

            try {
                while (true) {
                    patientIsWaitingDoctorOffice[this.id].acquire(); // wait for signal from nurse that a patient was added to their queue
                    
                    doctorQueueMutex.acquire();
                    int patientId = doctorQueue[this.id].poll(); // dequeue patient
                    doctorQueueMutex.release();
                    
                    patientEnteredDoctorOffice[patientId].acquire(); // wait for patient to enter office
                    
                    System.out.println("Doctor " + this.id + " listens to symptoms from patient " + patientId); // listen to patient's symptoms
                    patientFinished[patientId].release();
                }
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}