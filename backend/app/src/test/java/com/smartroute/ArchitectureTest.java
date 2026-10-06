package com.smartroute;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module boundary rules for the modular monolith. If one fails, the build fails, so boundaries
 * can't erode silently over time.
 */
@AnalyzeClasses(packages = "com.smartroute", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Modules (top-level packages) may depend on each other, but never in a cycle. */
    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("com.smartroute.(*)..").should().beFreeOfCycles();

    /** Other modules must use a module's service, not reach into its tables through its repository. */
    @ArchTest
    static final ArchRule repositoriesArePackagePrivate =
            classes().that().areAssignableTo(Repository.class).should().notBePublic();

    /** Controllers are entry points only; nothing should call them directly. */
    @ArchTest
    static final ArchRule controllersAreNotDependedOn =
            noClasses().should().dependOnClassesThat().areAnnotatedWith(RestController.class);

    /** The shared kernel must not know about business modules. */
    @ArchTest
    static final ArchRule commonDoesNotDependOnModules =
            noClasses().that().resideInAPackage("com.smartroute.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.smartroute.order..", "com.smartroute.fleet..", "com.smartroute.warehouse..",
                            "com.smartroute.demo..", "com.smartroute.auth..");

    /**
     * Every endpoint that changes data states who may call it. Forgetting the annotation would silently
     * allow any logged-in user (even a VIEWER), so the build fails instead. Login/refresh/logout are the
     * deliberate exception: they are what makes a caller authenticated in the first place.
     */
    @ArchTest
    static final ArchRule writeEndpointsDeclareAnAuthorizationRule =
            methods().that().areAnnotatedWith(PostMapping.class)
                    .or().areAnnotatedWith(PutMapping.class)
                    .or().areAnnotatedWith(DeleteMapping.class)
                    .or().areAnnotatedWith(PatchMapping.class)
                    .and().areDeclaredInClassesThat().doNotHaveSimpleName("AuthController")
                    .should().beAnnotatedWith(PreAuthorize.class)
                    .orShould().beDeclaredInClassesThat().areAnnotatedWith(PreAuthorize.class);

    /**
     * The assistant's tools may only read.
     *
     * <p>This is the rule that makes "the assistant cannot change anything" a property of the build rather
     * than a sentence in a prompt. A model that asked to cancel an order would have to find a tool that can,
     * and a tool that could would fail here: the names below are how every write in this codebase is spelled.
     */
    @ArchTest
    static final ArchRule assistantToolsOnlyRead =
            noClasses().that().resideInAPackage("com.smartroute.assistant.tools..")
                    // Whole-name matches, not prefixes: OrderResponse.createdAt() is a reader whose name
                    // starts with "create", and assignmentView() is a reader whose name starts with "assign".
                    .should().callMethodWhere(target(declaredIn(resideInAPackage("com.smartroute..")))
                            .and(target(nameMatching(
                            "save|saveAll|delete|deleteAll|deleteById|create|update|updateLocation|cancel"
                                    + "|assign|unassign|transition|markAssigned|deliveryUpdate|setActive"
                                    + "|changeStatus|rebuild|reserveCapacity|releaseCapacity|persist|merge"
                                    + "|remove|flush"))))
                    .because("the assistant is read-only; a tool that can write makes the prompt the only guard");
}
