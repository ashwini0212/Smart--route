package com.smartroute.assignment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes the assignment weights. One primary-key read per use; no cache to go stale. */
@Service
@Transactional(readOnly = true)
public class AssignmentConfigService {

    private final AssignmentConfigRepository repository;

    AssignmentConfigService(AssignmentConfigRepository repository) {
        this.repository = repository;
    }

    public AssignmentSettings current() {
        return load().toSettings();
    }

    @Transactional
    public AssignmentSettings update(AssignmentConfigRequest request) {
        AssignmentConfig config = load();
        config.update(request);
        repository.flush();
        return config.toSettings();
    }

    private AssignmentConfig load() {
        return repository.findById(AssignmentConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("assignment_config row 1 is missing (migration V4)"));
    }
}
