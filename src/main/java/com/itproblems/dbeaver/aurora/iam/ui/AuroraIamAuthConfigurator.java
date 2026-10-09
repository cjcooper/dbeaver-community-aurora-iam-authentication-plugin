package com.itproblems.dbeaver.aurora.iam.ui;

import com.itproblems.dbeaver.aurora.iam.AuroraIamConstants;
import com.itproblems.dbeaver.aurora.iam.AuroraIamTokenGenerator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.IObjectPropertyConfigurator;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

public class AuroraIamAuthConfigurator implements IObjectPropertyConfigurator<Object, DBPDataSourceContainer> {

    private Text usernameText;
    private Combo profileCombo;
    private Text regionText;
    private Text tokenHostText;

    @Override
    public void createControl(@NotNull Composite authPanel, Object object, @NotNull Runnable propertyChangeListener) {
        usernameText = UIUtils.createLabelText(authPanel, "Username", "");
        usernameText.addModifyListener(e -> propertyChangeListener.run());

        UIUtils.createControlLabel(authPanel, "AWS profile");
        profileCombo = new Combo(authPanel, SWT.DROP_DOWN);
        profileCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        profileCombo.setToolTipText("Profile from ~/.aws/config. Leave empty to use the default credentials chain.\n"
            + "For SSO profiles, run `aws sso login --profile <name>` when the session expires.");
        for (String profile : AuroraIamTokenGenerator.listProfiles()) {
            profileCombo.add(profile);
        }
        profileCombo.addModifyListener(e -> propertyChangeListener.run());

        regionText = UIUtils.createLabelText(authPanel, "AWS region", "");
        regionText.setMessage("auto (from hostname, then profile)");
        regionText.addModifyListener(e -> propertyChangeListener.run());

        tokenHostText = UIUtils.createLabelText(authPanel, "Token hostname", "");
        tokenHostText.setMessage("optional: RDS endpoint, if Host is a CNAME or proxy");
        tokenHostText.setToolTipText("The token is signed for this hostname instead of the connection Host.\n"
            + "Not needed for SSH tunnels; the original Host is used automatically.");
        tokenHostText.addModifyListener(e -> propertyChangeListener.run());
    }

    @Override
    public void loadSettings(@NotNull DBPDataSourceContainer dataSource) {
        DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
        usernameText.setText(CommonUtils.notEmpty(configuration.getUserName()));
        profileCombo.setText(CommonUtils.notEmpty(configuration.getAuthProperty(AuroraIamConstants.PROP_PROFILE)));
        regionText.setText(CommonUtils.notEmpty(configuration.getAuthProperty(AuroraIamConstants.PROP_REGION)));
        tokenHostText.setText(CommonUtils.notEmpty(configuration.getAuthProperty(AuroraIamConstants.PROP_TOKEN_HOST)));
    }

    @Override
    public void saveSettings(@NotNull DBPDataSourceContainer dataSource) {
        DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
        configuration.setUserName(usernameText.getText().trim());
        configuration.setUserPassword(null);
        configuration.setAuthProperty(AuroraIamConstants.PROP_PROFILE, emptyToNull(profileCombo.getText()));
        configuration.setAuthProperty(AuroraIamConstants.PROP_REGION, emptyToNull(regionText.getText()));
        configuration.setAuthProperty(AuroraIamConstants.PROP_TOKEN_HOST, emptyToNull(tokenHostText.getText()));
        // Nothing secret is stored, and this stops DBeaver from prompting for a password.
        dataSource.setSavePassword(true);
    }

    @Override
    public void resetSettings(@NotNull DBPDataSourceContainer dataSource) {
        loadSettings(dataSource);
    }

    @Override
    public boolean isComplete() {
        return usernameText != null && !usernameText.getText().isBlank();
    }

    @Override
    public String getErrorMessage() {
        return isComplete() ? null : "Database username is required for AWS IAM authentication";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
