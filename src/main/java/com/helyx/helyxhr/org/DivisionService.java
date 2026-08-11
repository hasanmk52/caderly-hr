package com.helyx.helyxhr.org;

import com.helyx.helyxhr.common.ConflictException;
import com.helyx.helyxhr.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Division CRUD and the delete-or-archive rule (PRD §13.2). */
@Service
public class DivisionService {

    private static final Logger log = LoggerFactory.getLogger(DivisionService.class);

    private final DivisionRepository divisions;
    private final DepartmentRepository departments;

    DivisionService(DivisionRepository divisions, DepartmentRepository departments) {
        this.divisions = divisions;
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public List<Division> listActive() {
        return divisions.findAllByArchivedFalseOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Division require(UUID id) {
        return divisions
                .findById(id)
                .orElseThrow(() -> new NotFoundException("DIVISION_NOT_FOUND", "Division not found"));
    }

    @Transactional
    public Division create(String name, @Nullable String description) {
        if (divisions.existsByNameIgnoreCase(name)) {
            throw new ConflictException(
                    "DIVISION_NAME_TAKEN", "A division named \"" + name + "\" already exists");
        }
        Division saved = divisions.save(Division.create(name, description));
        log.info("Created division {}", saved.requireId());
        return saved;
    }

    @Transactional
    public Division edit(UUID id, String name, @Nullable String description) {
        Division division = require(id);
        if (divisions.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException(
                    "DIVISION_NAME_TAKEN", "A division named \"" + name + "\" already exists");
        }
        division.rename(name);
        division.updateDescription(description);
        return division;
    }

    /**
     * PRD §13.2: hard delete only when nothing references the row, otherwise archive. A Division
     * "in use" means it still has non-archived Departments assigned to it.
     */
    @Transactional
    public DeleteOutcome deleteOrArchive(UUID id) {
        Division division = require(id);
        if (departments.countByDivisionIdAndArchivedFalse(id) > 0) {
            division.archive();
            log.info("Archived division {} (has active departments)", id);
            return DeleteOutcome.ARCHIVED;
        }
        divisions.delete(division);
        log.info("Deleted division {}", id);
        return DeleteOutcome.DELETED;
    }
}
