// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * La console était servie en {@code no-store} : chaque ouverture retéléchargeait ~760 Ko.
 *
 * <p>Les URL des modules ne portent pas de version (les imports ES se citent par nom, sans étape de
 * build pour les réécrire) : un {@code max-age} long garderait une console d'avant la mise à jour.
 * D'où {@code no-cache}, posé par {@code spring.web.resources.cache}, et une revalidation par ETag
 * calculée sur le contenu plutôt que sur la date d'entrée du JAR, qui change à chaque build sans
 * que le fichier change, et pourrait rester identique entre deux images qui diffèrent.
 */
@Configuration(proxyBeanMethods = false)
class StaticResourceConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> consoleEtags() {
        // Bornée aux ressources de la console : sur l'API, le filtre mettrait en tampon les flux SSE.
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        // Faible : Tomcat refuse de compresser une réponse qui porte un ETag fort, puisque le corps
        // compressé n'est plus octet pour octet celui que l'ETag désigne.
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        // Pas « / » ni « /app » : ce sont des renvois internes vers index.html, que le tampon du
        // filtre rendait vides. Le navigateur charge la page par eux, les modules par /assets.
        registration.addUrlPatterns("/index.html", "/app/index.html", "/assets/*");
        registration.setName("consoleEtags");
        return registration;
    }
}
