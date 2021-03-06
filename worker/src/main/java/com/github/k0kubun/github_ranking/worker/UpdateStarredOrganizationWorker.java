package com.github.k0kubun.github_ranking.worker;

import com.github.k0kubun.github_ranking.github.GitHubClient;
import com.github.k0kubun.github_ranking.model.Organization;
import com.github.k0kubun.github_ranking.model.UpdateUserJob;
import com.github.k0kubun.github_ranking.model.User;
import com.github.k0kubun.github_ranking.repository.DatabaseLock;
import com.github.k0kubun.github_ranking.repository.PaginatedOrganizations;
import com.github.k0kubun.github_ranking.repository.dao.UpdateUserJobDao;
import com.github.k0kubun.github_ranking.repository.dao.UserDao;
import io.sentry.Sentry;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.skife.jdbi.v2.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// This job must finish within TIMEOUT_MINUTES (1 min). Otherwise it will be infinitely retried.
public class UpdateStarredOrganizationWorker
        extends UpdateUserWorker
{
    private static final Logger LOG = LoggerFactory.getLogger(UpdateStarredOrganizationWorker.class);

    public UpdateStarredOrganizationWorker(DataSource dataSource)
    {
        super(dataSource);
    }

    // Dequeue a record from update_user_jobs and call updateUser().
    @Override
    public void perform()
            throws Exception
    {
        try (Handle handle = dbi.open()) {
            DatabaseLock lock = new DatabaseLock(handle, this);
            PaginatedOrganizations paginatedOrganizations = new PaginatedOrganizations(handle);

            List<Organization> orgs;
            while (!(orgs = paginatedOrganizations.nextOrgs()).isEmpty()) {
                for (Organization org : orgs) {
                    if (isStopped) {
                        return;
                    }

                    User user = org.toUser();
                    // Skip if it's recently updated
                    if (user.isUpdatedWithinDays(5)) {
                        LOG.info("UpdateStarredOrganizationWorker skipped: (userId = " + user.getId() + ", login = " + user.getLogin() + ", updatedAt = " + user.getUpdatedAt().toString() + ")");
                        continue;
                    }

                    try {
                        lock.withUserUpdate(user.getId(), () -> {
                            LOG.info("UpdateStarredOrganizationWorker started: (userId = " + user.getId() + ", login = " + user.getLogin() + ")");
                            GitHubClient client = clientBuilder.buildEnabled();
                            updateUser(handle, user, client);
                            LOG.info("UpdateStarredOrganizationWorker finished: (userId = " + user.getId() + ", login = " + user.getLogin() + ")");
                        });
                    }
                    catch (Exception e) {
                        Sentry.capture(e);
                        LOG.error("Error in UpdateStarredOrganizationWorker! (userId = " + user.getId() + "): " + e.toString() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
