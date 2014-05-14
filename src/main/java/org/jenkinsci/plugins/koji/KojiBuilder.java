package org.jenkinsci.plugins.koji;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.apache.xmlrpc.XmlRpcException;
import org.jenkinsci.plugins.koji.xmlrpc.KojiClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Map;


/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link KojiBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #kojiBuild})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked.
 *
 * @author Vaclav Tunka
 */
public class KojiBuilder extends Builder {

    private final String kojiBuild;
    private final String kojiTarget;
    private final String kojiPackage;
    private final String kojiOptions;
    private static String kojiTask;
    private boolean kojiScratchBuild;
    private final String kojiScmUrl;

    private transient BuildListener listener;
    private transient KojiClient koji;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    @SuppressWarnings("UnusedDeclaration")
    public KojiBuilder(String kojiBuild, String kojiTarget, String kojiPackage, String kojiOptions, String kojiTask, boolean kojiScratchBuild, String kojiScmUrl) {
        this.kojiBuild = kojiBuild;
        this.kojiTarget = kojiTarget;
        this.kojiPackage = kojiPackage;
        this.kojiOptions = kojiOptions;
        this.kojiTask = kojiTask;
        this.kojiScmUrl = kojiScmUrl;
        this.kojiScratchBuild = kojiScratchBuild;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getKojiBuild() {
        return kojiBuild;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getKojiTarget() {
        return kojiTarget;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getKojiPackage() {
        return kojiPackage;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getKojiOptions() {
        return kojiOptions;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getKojiTask() {
        return kojiTask;
    }

    public boolean isKojiScratchBuild() {
        return kojiScratchBuild;
    }

    public void setKojiScratchBuild(boolean kojiScratchBuild) {
        this.kojiScratchBuild = kojiScratchBuild;
    }

    @SuppressWarnings("UnusedDeclaration")
    public String getKojiScmUrl() {
        return kojiScmUrl;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
//        printDebugInfo();
        try {
            init(listener);
        } catch (XmlRpcException e) {
            listener.getLogger().println("\n[Koji integration] " + e.getMessage());
        }

        boolean kojiRunSucceeded = false;
        KojiLauncher kojiLauncher = new KojiLauncher(build, launcher, listener);

        if (kojiTask.equals(KojiTask.mavenBuild.name())) {
            listener.getLogger().println("\n[Koji integration] Running maven build build for package " + kojiPackage + " in tag " + kojiTarget);
            kojiLauncher.mavenBuildCommand(isScratchToString(), kojiTarget, kojiScmUrl);
            kojiRunSucceeded = kojiLauncher.callKoji();

        } else if (kojiTask.equals(KojiTask.download.name())) {
            listener.getLogger().println("\n[Koji integration] Downloading artifacts for build " + kojiBuild);
            kojiLauncher.downloadCommand(kojiBuild);
            kojiRunSucceeded = kojiLauncher.callKoji();
        } else if (kojiTask.equals(KojiTask.listLatest.name())) {
            listener.getLogger().println("\n[Koji integration] Listing latest build information for package " + kojiPackage + " in tag " + kojiTarget);
            kojiRunSucceeded = getLatestBuilds(kojiPackage, kojiTarget);
        } else if (kojiTask.equals(KojiTask.moshimoshi.name())) {
            kojiLauncher.moshiMoshiCommand();
            kojiLauncher.callKoji();
            // always return true, as moshimoshi sometimes returns non-international characters, that cannot be logged
            kojiRunSucceeded = true;
        }

//        listener.getLogger().println("\n[Koji integration] Watching task");
//        kojiLauncher.watchTaskCommand("366");
//        kojiLauncher.callKoji();

        return kojiRunSucceeded;
    }

    private String isScratchToString() {
        if (kojiScratchBuild) {
            return "--scratch";
        } else
            return "";
    }

    private boolean getLatestBuilds(String pkg, String tag) {
        Map<String, String> result = null;

        listener.getLogger().println("\n[Koji integration] Searching latest build for package " + pkg + " in tag " + tag);
        try {
            result = koji.getLatestBuilds(tag, pkg);
        } catch (XmlRpcException e) {
            if (e.getMessage() == "empty") {
                listener.getLogger().println("[Koji integration] No package " + pkg + " found for tag " + tag);
                return false;
            }
            else {
                listener.getLogger().println(e.getMessage());
                return false;
            }
        }
        for (Map.Entry<String, String> entry : result.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            listener.getLogger().println(key + ": " + value);
        }

        return true;
    }

    private void getBuildInfo(String build) {
        Map<String, String> buildInfo = null;

        listener.getLogger().println("\n[Koji integration] Searching for build information for " + build);
        try {
            buildInfo = koji.getBuildInfo(build);
        } catch (XmlRpcException e) {
            if (e.getMessage() == "empty") {
                listener.getLogger().println("[Koji integration] No build with id=" + build + " found in the database.");
                return;
            }
            else
                e.printStackTrace();
        }
        for (Map.Entry<String, String> entry : buildInfo.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            listener.getLogger().println(key + ": " + value);
        }
    }

    private void init(BuildListener listener) throws XmlRpcException {
        this.listener = listener;
        this.koji = KojiClient.getKojiClient(getDescriptor().getKojiInstanceURL());

        if (getDescriptor().getAuthentication().equals(Authentication.plain.name())) {
            koji.login(getDescriptor().getKojiUsername(), getDescriptor().getKojiPassword());
        }
    }

    private void printDebugInfo() {
        listener.getLogger().println("This is the selected Koji Instance: " + getDescriptor().getKojiInstanceURL());
        listener.getLogger().println("This is the selected Koji Build: " + kojiBuild);
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link KojiBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugins/hello_world/KojiBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private String kojiInstanceURL;
        private String authentication;
        private String kojiUsername;
        private String kojiPassword;
        private String sslCertificatePath;

        private transient ListBoxModel authModel = new ListBoxModel(
                new ListBoxModel.Option("Username / Password", Authentication.plain.name()),
                new ListBoxModel.Option("OpenSSL", Authentication.openSSL.name()),
                new ListBoxModel.Option("Kerberos (TBD)", Authentication.kerberos.name())
        );

        private transient ListBoxModel kojiTaskModel = new ListBoxModel(
                new ListBoxModel.Option("Koji moshimoshi (validate client configuration)", KojiTask.moshimoshi.name()),
                new ListBoxModel.Option("Run a new maven build", KojiTask.mavenBuild.name()),
                new ListBoxModel.Option("Download maven build", KojiTask.download.name()),
                new ListBoxModel.Option("List latest build for package", KojiTask.listLatest.name())
        );

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            super(KojiBuilder.class);
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'kojiInstanceURL'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckKojiInstanceURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a Koji instance URL");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the Koji instance URL too short?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * Fills the items for authentication for global configuration.
         * @return
         */
        public ListBoxModel doFillAuthenticationItems(){
            if (authentication == null) {
                authModel.get(0).selected = true;
                return authModel;
            }

            for (ListBoxModel.Option option : authModel) {
                if (option.name.equals(Authentication.plain.name()))
                    option.selected = authentication.equals(Authentication.plain.name());
                else if (option.name.equals(Authentication.openSSL.name()))
                    option.selected = authentication.equals(Authentication.openSSL.name());
                else if (option.name.equals(Authentication.kerberos.name()))
                    option.selected = authentication.equals(Authentication.kerberos.name());
            }

            return authModel;
        }

        /**
         * Fills the Koji task options for project configurations.
         * @return
         */
        public ListBoxModel doFillKojiTaskItems(){
            for (ListBoxModel.Option option : kojiTaskModel) {
                if (option.name.equals(KojiTask.moshimoshi.name()))
                    option.selected = kojiTask.equals(KojiTask.moshimoshi.name());
                else if (option.name.equals(KojiTask.download.name()))
                    option.selected = kojiTask.equals(KojiTask.download.name());
                else if (option.name.equals(KojiTask.listLatest.name()))
                    option.selected = kojiTask.equals(KojiTask.listLatest.name());
                else if (option.name.equals(KojiTask.mavenBuild.name()))
                    option.selected = kojiTask.equals(KojiTask.mavenBuild.name());
            }

            return kojiTaskModel;
        }


        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Koji integration";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            kojiInstanceURL = formData.getString("kojiInstanceURL");
            authentication = formData.getString("authentication");
            kojiUsername = formData.getString("kojiUsername");
            kojiPassword = formData.getString("kojiPassword");
            sslCertificatePath = formData.getString("sslCertificatePath");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * Get Koji Instance URL.
         */
        public String getKojiInstanceURL() {
            return kojiInstanceURL;
        }

        public void setKojiInstanceURL(String kojiInstanceURL) {
            this.kojiInstanceURL = kojiInstanceURL;
        }

        @SuppressWarnings("UnusedDeclaration")
        public String getAuthentication() {
            return authentication;
        }

        public void setAuthentication(String authentication) {
            this.authentication = authentication;
        }

        @SuppressWarnings("UnusedDeclaration")
        public String getKojiUsername() {
            return kojiUsername;
        }

        public void setKojiUsername(String kojiUsername) {
            this.kojiUsername = kojiUsername;
        }

        @SuppressWarnings("UnusedDeclaration")
        public String getKojiPassword() {
            return kojiPassword;
        }

        public void setKojiPassword(String kojiPassword) {
            this.kojiPassword = kojiPassword;
        }

        @SuppressWarnings("UnusedDeclaration")
        public String getSslCertificatePath() {
            return sslCertificatePath;
        }

        public void setSslCertificatePath(String sslCertificatePath) {
            this.sslCertificatePath = sslCertificatePath;
        }
    }

    enum Authentication {
        plain, openSSL, kerberos
    }

    enum KojiTask {
        mavenBuild, download, listLatest, moshimoshi
    }
}