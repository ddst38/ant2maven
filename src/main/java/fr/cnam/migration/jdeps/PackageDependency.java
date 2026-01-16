package fr.cnam.migration.jdeps;

/**
 * Represente une dependance entre deux packages.
 */
public record PackageDependency(
    String sourcePackage,    // Package source (ex: fr.cnam.app.service)
    String targetPackage,    // Package cible (ex: java.util)
    String targetModule      // Module JDK cible si applicable (ex: java.base)
) {}
