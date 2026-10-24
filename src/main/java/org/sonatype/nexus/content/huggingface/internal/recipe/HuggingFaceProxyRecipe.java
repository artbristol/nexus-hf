/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import javax.annotation.Nonnull;

import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.internal.HuggingFaceFormat;
import org.sonatype.nexus.repository.huggingface.internal.HuggingFaceSecurityFacet;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Matcher;
import org.sonatype.nexus.repository.view.Route;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;
import org.sonatype.nexus.repository.view.matchers.ActionMatcher;
import org.sonatype.nexus.repository.view.matchers.RegexMatcher;

import jakarta.inject.Provider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.repository.http.HttpHandlers.notFound;
import static org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers.and;

/**
 * Hugging Face proxy repository recipe.
 */
@AvailabilityVersion(from = "1.0")
@Component
@Qualifier(HuggingFaceProxyRecipe.NAME)
public class HuggingFaceProxyRecipe
    extends RecipeSupport
{
  public static final String NAME = "huggingface-proxy";

  private final Provider<HuggingFaceSecurityFacet> securityFacet;

  private final Provider<ConfigurableViewFacet> viewFacet;

  private final Provider<HuggingFaceContentFacet> contentFacet;

  private final Provider<HttpClientFacet> httpClientFacet;

  private final Provider<NegativeCacheFacet> negativeCacheFacet;

  private final Provider<HuggingFaceProxyFacet> proxyFacet;

  private final Provider<LastAssetMaintenanceFacet> maintenanceFacet;

  private final Provider<SearchFacet> searchFacet;

  private final Provider<BrowseFacet> browseFacet;

  private final Provider<PurgeUnusedFacet> purgeUnusedFacet;

  private final TimingHandler timingHandler;

  private final SecurityHandler securityHandler;

  private final RoutingRuleHandler routingRuleHandler;

  private final ExceptionHandler exceptionHandler;

  private final HandlerContributor handlerContributor;

  private final NegativeCacheHandler negativeCacheHandler;

  private final ConditionalRequestHandler conditionalRequestHandler;

  private final PartialFetchHandler partialFetchHandler;

  private final ContentHeadersHandler contentHeadersHandler;

  private final HuggingFaceHeadersHandler huggingFaceHeadersHandler;

  private final HuggingFaceErrorHandler huggingFaceErrorHandler;

  private final LastDownloadedHandler lastDownloadedHandler;

  private final ProxyHandler proxyHandler;

  @Autowired
  @SuppressWarnings("squid:S00107") // suppress constructor parameter count
  public HuggingFaceProxyRecipe(
      @Qualifier(ProxyType.NAME) final Type type,
      @Qualifier(HuggingFaceFormat.NAME) final Format format,
      final Provider<HuggingFaceSecurityFacet> securityFacet,
      final Provider<ConfigurableViewFacet> viewFacet,
      final Provider<HuggingFaceContentFacet> contentFacet,
      final Provider<HttpClientFacet> httpClientFacet,
      final Provider<NegativeCacheFacet> negativeCacheFacet,
      final Provider<HuggingFaceProxyFacet> proxyFacet,
      final Provider<LastAssetMaintenanceFacet> maintenanceFacet,
      final Provider<SearchFacet> searchFacet,
      final Provider<BrowseFacet> browseFacet,
      final Provider<PurgeUnusedFacet> purgeUnusedFacet,
      final TimingHandler timingHandler,
      final SecurityHandler securityHandler,
      final RoutingRuleHandler routingRuleHandler,
      final ExceptionHandler exceptionHandler,
      final HandlerContributor handlerContributor,
      final NegativeCacheHandler negativeCacheHandler,
      final ConditionalRequestHandler conditionalRequestHandler,
      final PartialFetchHandler partialFetchHandler,
      final ContentHeadersHandler contentHeadersHandler,
      final HuggingFaceHeadersHandler huggingFaceHeadersHandler,
      final HuggingFaceErrorHandler huggingFaceErrorHandler,
      final LastDownloadedHandler lastDownloadedHandler,
      final ProxyHandler proxyHandler)
  {
    super(type, format);
    this.securityFacet = requireNonNull(securityFacet);
    this.viewFacet = requireNonNull(viewFacet);
    this.contentFacet = requireNonNull(contentFacet);
    this.httpClientFacet = requireNonNull(httpClientFacet);
    this.negativeCacheFacet = requireNonNull(negativeCacheFacet);
    this.proxyFacet = requireNonNull(proxyFacet);
    this.maintenanceFacet = requireNonNull(maintenanceFacet);
    this.searchFacet = requireNonNull(searchFacet);
    this.browseFacet = requireNonNull(browseFacet);
    this.purgeUnusedFacet = requireNonNull(purgeUnusedFacet);
    this.timingHandler = requireNonNull(timingHandler);
    this.securityHandler = requireNonNull(securityHandler);
    this.routingRuleHandler = requireNonNull(routingRuleHandler);
    this.exceptionHandler = requireNonNull(exceptionHandler);
    this.handlerContributor = requireNonNull(handlerContributor);
    this.negativeCacheHandler = requireNonNull(negativeCacheHandler);
    this.conditionalRequestHandler = requireNonNull(conditionalRequestHandler);
    this.partialFetchHandler = requireNonNull(partialFetchHandler);
    this.contentHeadersHandler = requireNonNull(contentHeadersHandler);
    this.huggingFaceHeadersHandler = requireNonNull(huggingFaceHeadersHandler);
    this.huggingFaceErrorHandler = requireNonNull(huggingFaceErrorHandler);
    this.lastDownloadedHandler = requireNonNull(lastDownloadedHandler);
    this.proxyHandler = requireNonNull(proxyHandler);
  }

  @Override
  public void apply(@Nonnull final Repository repository) throws Exception {
    repository.attach(securityFacet.get());
    repository.attach(configure(viewFacet.get()));
    repository.attach(contentFacet.get());
    repository.attach(httpClientFacet.get());
    repository.attach(negativeCacheFacet.get());
    repository.attach(proxyFacet.get());
    repository.attach(maintenanceFacet.get());
    repository.attach(searchFacet.get());
    repository.attach(browseFacet.get());
    repository.attach(purgeUnusedFacet.get());
  }

  private ViewFacet configure(final ConfigurableViewFacet facet) {
    Router.Builder builder = new Router.Builder();
    builder.route(proxyRoute(and(
        new ActionMatcher(HttpMethods.GET, HttpMethods.HEAD), new RegexMatcher(HuggingFacePaths.FILE_REGEX))));
    builder.route(proxyRoute(and(
        new ActionMatcher(HttpMethods.GET), new RegexMatcher(HuggingFacePaths.API_REGEX))));
    builder.defaultHandlers(notFound());
    facet.configure(builder.create());
    return facet;
  }

  private Route proxyRoute(final Matcher matcher) {
    return new Route.Builder()
        .matcher(matcher)
        .handler(timingHandler)
        .handler(securityHandler)
        .handler(routingRuleHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(huggingFaceErrorHandler)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(huggingFaceHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(proxyHandler)
        .create();
  }
}
