The problem is that currently the logic is spread accross around 15 services. Hard to follow, hard to debug and understand.
  Also long running tasks are not cancelable. So the idea is that every REST call, starts a process.
ProcessManager keeps track of them and allows cancelation of processes.
I'm still thinking about the structure though. I now included the metadata about the process into the process and my abstract base class already grew larger than I like.
I don't really like inheritance anymore. It tends to always create more problems in the long run. But it's such an obvious solution.

@Component(immediate = true, service = ProcessManager.class)
public class ProcessManager {

  private final ExecutorService executorService = Executors.newFixedThreadPool(10);
  private final Map<String, SpiritLinkProcess> processMap = new ConcurrentHashMap<>(); <- here i wanna store processes to keep track of them

  //submits the process, sets the future and returns the processId to the caller, so he can use it, to monitor the state of the process
  public String submitProcess(SpiritLinkProcess process) {
    Future<?> future = executorService.submit(process);
    process.setFuture(future);
    processMap.put(process.getProcessId(), process);
    return process.getProcessId();
  }

  //earlier i thought about keeping track of the child processes here, but now decided to encapsulate it more. Not too sure about this yet, though.
  //Another things is ressource management - some long running tasks my spawn many child tasks. I'm afraid that nested executorservices will make ressource management problematic,
  // so for now I decided to have a single executorService here, which the processes will use to spawn their children. But it's somewhat unnatural to me.
  public String submitChildProcess(String parentProcessId, SpiritLinkProcess childProcess) {
    Future<?> future = executorService.submit(childProcess);
    childProcess.setFuture(future);
    processMap.put(childProcess.getProcessId(), childProcess);

    SpiritLinkProcess parentProcess = processMap.get(parentProcessId);
    if (parentProcess != null) {
      parentProcess.addChildProcess(childProcess);
    }

    return childProcess.getProcessId();
  }

  public void cancelProcess(String processId) {
    SpiritLinkProcess process = processMap.get(processId);
    if (process != null) {
      process.cancel();
    }
  }

  public boolean isProcessRunning(String processId) {
    SpiritLinkProcess process = processMap.get(processId);
    return process != null && !process.isDone();
  }

  public List<String> getChildProcesses(String parentProcessId) {
    SpiritLinkProcess parentProcess = processMap.get(parentProcessId);
    if (parentProcess != null) {
      return parentProcess.getChildProcessIds();
    }
    return Collections.emptyList();
  }
}


// this is the abstract base class <- i already hate it
public abstract class SpiritLinkProcess implements Callable<Void> {

  private final String processId;
  private final long siteId;
  private final long userId;
  private final long companyId;
  private final Locale defaultLocale;
  private final ProcessManager processManager;
  private final List<SpiritLinkProcess> childProcesses = new ArrayList<>();
  private Future<?> future;

  public SpiritLinkProcess(long siteId, long userId, long companyId, Locale defaultLocale, ProcessManager processManager) {
    this.processId = UUID.randomUUID().toString(); <- every process generates it's own uuid
    this.siteId = siteId;
    this.userId = userId;
    this.companyId = companyId;
    this.defaultLocale = defaultLocale;
    //because of the executorservice and the way data is currently stored, we have to keep a reference to the store manager for child processes
    this.processManager = processManager;
    this.setup();
  }

  @Override
  public abstract Void call() throws Exception;

  ...getters

  public void setFuture(Future<?> future) {
    this.future = future;
  }

  public boolean isDone() {
    return future != null && future.isDone();
  }

  public void cancel() {
    if (future != null) {
      future.cancel(true);
    }
    for (SpiritLinkProcess child : childProcesses) {
      child.cancel();
    }
  }

  public void addChildProcess(SpiritLinkProcess childProcess) {
    childProcesses.add(childProcess);
  }

  public List<String> getChildProcessIds() {
    List<String> ids = new ArrayList<>();
    for (SpiritLinkProcess child : childProcesses) {
      ids.add(child.getProcessId());
    }
    return ids;
  }

  protected void submitChildProcess(SpiritLinkProcess childProcess) {
    // here we use it to spawn children
    processManager.submitChildProcess(this.processId, childProcess);
  }

  private void setup() {
    try {
      LocaleThreadLocal.setDefaultLocale(defaultLocale);
      Util.setPermissionChecker(userId);
    } catch (Exception e) {
      throw new RuntimeException("Error during process setup! Process not executed!", e);
    }
  }
}

// this would be a long running task spawning many child tasks
public class CreateFragmentsProcess extends SpiritLinkProcess {

  private static final Log log = LogFactoryUtil.getLog(CreateFragmentsProcess.class);

  private final boolean update;
  private final CustomFragmentService customFragmentService;
  private final MongoDb mongoDb;

  public CreateFragmentsProcess(
    ...constructor...
}

@Override
public Void call() {
  try {
    createFragmentsFromFsData();
  } catch (Exception e) {
    log.error("CreateFragmentsFromCaasProcess failed!", e);
    throw new FragmentCreationException("CreateFragmentsFromCaasProcess failed!", e);
  }

  return null;
}

private void createFragmentsFromFsData() throws LiferayFirstSpiritConnectorException {
  if (!update) {
    customFragmentService.deleteFragments();
  }

  log.info((update ? "Updating" : "Creating") + " fragments...");

  customFragmentService.createFragmentCollection(siteId(), userId(), companyId());

  // here we'll iterate over each component found and spawn a new child process for it. These processes tend be end very quickly, yet in case you could in future still monitor and cancel them. <- actually probably also restart! <- need to implement something for that. That's awesome.
  // earlier an executorservice was used in this class to spawn child processes
  mongoDb.getAllComponents().forEach(child ->
    submitChildProcess(new CreateFragmentProcess(siteId(), userId(), companyId(), defaultLocale(), update, customFragmentService, child, processManager())));

  log.info("Fragments " + (update ? "updated" : "created") + ".");
}
}

// this would be the child process <- all process need to extend the base class.
class CreateFragmentProcess extends SpiritLinkProcess {

  private static final Log log = LogFactoryUtil.getLog(CreateFragmentProcess.class);

  private final boolean update;
  private final CustomFragmentService customFragmentService;
  private final ComponentWrapper wrapper;

  public CreateFragmentProcess(
    ...constructor...
}

@Override
public Void call() throws Exception {
  try {
    customFragmentService.createFragment(siteId(), userId(), companyId(), wrapper, update);
  } catch (Exception e) {
    log.error("Failed to create fragment for component: " + wrapper.getId(), e);
    throw new FragmentCreationException("Failed to create fragment for component: " + wrapper.getId(), e);
  }
  return null;
}
}
