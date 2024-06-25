import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// ProcessContext.java
// New class introduced to encapsulate process metadata, reducing the size and complexity of SpiritLinkProcess
public class ProcessContext {
    private final String processId;
    private final long siteId;
    private final long userId;
    private final long companyId;
    private final Locale defaultLocale;

    public ProcessContext(long siteId, long userId, long companyId, Locale defaultLocale) {
        this.processId = UUID.randomUUID().toString(); // Process ID generation moved here
        this.siteId = siteId;
        this.userId = userId;
        this.companyId = companyId;
        this.defaultLocale = defaultLocale;
    }

    // Getters for process metadata
    public String getProcessId() {
        return processId;
    }

    public long getSiteId() {
        return siteId;
    }

    public long getUserId() {
        return userId;
    }

    public long getCompanyId() {
        return companyId;
    }

    public Locale getDefaultLocale() {
        return defaultLocale;
    }
}

// SpiritLinkProcess.java
public abstract class SpiritLinkProcess implements Callable<Void> {
    private final ProcessContext context; // Changed to use ProcessContext for metadata
    private final List<SpiritLinkProcess> childProcesses = new ArrayList<>();
    private Future<?> future;

    // Constructor now takes a ProcessContext instead of individual metadata parameters
    public SpiritLinkProcess(ProcessContext context) {
        this.context = context;
        this.setup(); // Setup method moved here to ensure it's called for all processes
    }

    @Override
    public abstract Void call() throws Exception;

    public ProcessContext getContext() {
        return context; // Accessor for the context
    }

    public String getProcessId() {
        return context.getProcessId(); // Process ID retrieval now through context
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public boolean isDone() {
        return future != null && future.isDone();
    }

    public void cancel() {
        if (future != null) {
            future.cancel(true); // Cancels the process
        }
        for (SpiritLinkProcess child : childProcesses) {
            child.cancel(); // Cancels all child processes
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
        // Submits child process using ProcessManager
        processManager.submitChildProcess(this.getProcessId(), childProcess);
    }

    private void setup() {
        try {
            LocaleThreadLocal.setDefaultLocale(context.getDefaultLocale()); // Locale setup moved to ProcessContext
            Util.setPermissionChecker(context.getUserId()); // Permission setup using ProcessContext
        } catch (Exception e) {
            throw new RuntimeException("Error during process setup! Process not executed!", e);
        }
    }
}

// ProcessManager.java
@Component(immediate = true, service = ProcessManager.class)
public class ProcessManager {
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private final Map<String, SpiritLinkProcess> processMap = new ConcurrentHashMap<>();

    public String submitProcess(SpiritLinkProcess process) {
        Future<?> future = executorService.submit(process);
        process.setFuture(future);
        processMap.put(process.getProcessId(), process);
        return process.getProcessId();
    }

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

// CreateFragmentsProcess.java
public class CreateFragmentsProcess extends SpiritLinkProcess {
    private static final Log log = LogFactoryUtil.getLog(CreateFragmentsProcess.class);

    private final boolean update;
    private final CustomFragmentService customFragmentService;
    private final MongoDb mongoDb;

    // Constructor updated to use ProcessContext
    public CreateFragmentsProcess(ProcessContext context, boolean update, CustomFragmentService customFragmentService, MongoDb mongoDb) {
        super(context);
        this.update = update;
        this.customFragmentService = customFragmentService;
        this.mongoDb = mongoDb;
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

        customFragmentService.createFragmentCollection(getContext().getSiteId(), getContext().getUserId(), getContext().getCompanyId());

        // ProcessContext is now used for child processes
        mongoDb.getAllComponents().forEach(child ->
                submitChildProcess(new CreateFragmentProcess(
                        new ProcessContext(getContext().getSiteId(), getContext().getUserId(), getContext().getCompanyId(), getContext().getDefaultLocale()),
                        update, customFragmentService, child)));

        log.info("Fragments " + (update ? "updated" : "created") + ".");
    }
}

// CreateFragmentProcess.java
public class CreateFragmentProcess extends SpiritLinkProcess {
    private static final Log log = LogFactoryUtil.getLog(CreateFragmentProcess.class);

    private final boolean update;
    private final CustomFragmentService customFragmentService;
    private final ComponentWrapper wrapper;

    // Constructor updated to use ProcessContext
    public CreateFragmentProcess(ProcessContext context, boolean update, CustomFragmentService customFragmentService, ComponentWrapper wrapper) {
        super(context);
        this.update = update;
        this.customFragmentService = customFragmentService;
        this.wrapper = wrapper;
    }

    @Override
    public Void call() throws Exception {
        try {
            customFragmentService.createFragment(getContext().getSiteId(), getContext().getUserId(), getContext().getCompanyId(), wrapper, update);
        } catch (Exception e) {
            log.error("Failed to create fragment for component: " + wrapper.getId(), e);
            throw new FragmentCreationException("Failed to create fragment for component: " + wrapper.getId(), e);
        }
        return null;
    }
}


