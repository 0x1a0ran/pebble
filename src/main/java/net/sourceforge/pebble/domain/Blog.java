/*
 * Copyright (c) 2003-2011, Simon Brown
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in
 *     the documentation and/or other materials provided with the
 *     distribution.
 *
 *   - Neither the name of Pebble nor the names of its contributors may
 *     be used to endorse or promote products derived from this software
 *     without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package net.sourceforge.pebble.domain;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Lists;
import net.sourceforge.pebble.*;
import net.sourceforge.pebble.aggregator.NewsFeedCache;
import net.sourceforge.pebble.aggregator.NewsFeedEntry;
import net.sourceforge.pebble.api.confirmation.CommentConfirmationStrategy;
import net.sourceforge.pebble.api.confirmation.TrackBackConfirmationStrategy;
import net.sourceforge.pebble.api.decorator.ContentDecorator;
import net.sourceforge.pebble.api.decorator.FeedDecorator;
import net.sourceforge.pebble.api.decorator.PageDecorator;
import net.sourceforge.pebble.api.event.EventDispatcher;
import net.sourceforge.pebble.api.event.blog.BlogEvent;
import net.sourceforge.pebble.api.event.blog.BlogListener;
import net.sourceforge.pebble.api.event.blogentry.BlogEntryListener;
import net.sourceforge.pebble.api.event.comment.CommentListener;
import net.sourceforge.pebble.api.event.trackback.TrackBackListener;
import net.sourceforge.pebble.api.openid.OpenIdCommentAuthorProvider;
import net.sourceforge.pebble.api.permalink.PermalinkProvider;
import net.sourceforge.pebble.confirmation.DefaultConfirmationStrategy;
import net.sourceforge.pebble.dao.CategoryDAO;
import net.sourceforge.pebble.dao.DAOFactory;
import net.sourceforge.pebble.dao.PersistenceException;
import net.sourceforge.pebble.decorator.ContentDecoratorChain;
import net.sourceforge.pebble.decorator.HideUnapprovedResponsesDecorator;
import net.sourceforge.pebble.event.AuditListener;
import net.sourceforge.pebble.event.DefaultEventDispatcher;
import net.sourceforge.pebble.event.EventListenerList;
import net.sourceforge.pebble.event.blogentry.EmailSubscriptionListener;
import net.sourceforge.pebble.index.*;
import net.sourceforge.pebble.index.file.*;
import net.sourceforge.pebble.index.lucene.LuceneSearchIndex;
import net.sourceforge.pebble.logging.AbstractLogger;
import net.sourceforge.pebble.logging.CombinedLogFormatLogger;
import net.sourceforge.pebble.permalink.DefaultPermalinkProvider;
import net.sourceforge.pebble.util.StringUtils;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.lang.reflect.Constructor;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents a blog.
 *
 * @author    Simon Brown
 */
public class Blog extends AbstractBlog {

  private static final Log log = LogFactory.getLog(Blog.class);

  public static final String ABOUT_KEY = "about";
  public static final String EMAIL_KEY = "email";
  public static final String BLOG_OWNERS_KEY = "blogOwners";
  public static final String BLOG_PUBLISHERS_KEY = "blogPublishers";
  public static final String BLOG_CONTRIBUTORS_KEY = "blogContributors";
  public static final String BLOG_READERS_KEY = "blogReaders";
  public static final String PRIVATE_KEY = "private";
  public static final String LUCENE_ANALYZER_KEY = "luceneAnalyzer";
  public static final String CONTENT_DECORATORS_KEY = "decorators";
  public static final String BLOG_LISTENERS_KEY = "blogListeners";
  public static final String BLOG_ENTRY_LISTENERS_KEY = "blogEntryListeners";
  public static final String COMMENT_LISTENERS_KEY = "commentListeners";
  public static final String TRACKBACK_LISTENERS_KEY = "trackBackListeners";
  public static final String EVENT_DISPATCHER_KEY = "eventDispatcher";
  public static final String LOGGER_KEY = "logger";
  public static final String PERMALINK_PROVIDER_KEY = "permalinkProviderName";
  public static final String COMMENT_CONFIRMATION_STRATEGY_KEY = "commentConfirmationStrategy";
  public static final String TRACKBACK_CONFIRMATION_STRATEGY_KEY = "trackBackConfirmationStrategy";
  public static final String RICH_TEXT_EDITOR_FOR_COMMENTS_ENABLED_KEY = "richTextEditorForCommentsEnabled";
  public static final String GRAVATAR_SUPPORT_FOR_COMMENTS_ENABLED_KEY = "gravatarSupportForCommentsEnabled";
  public static final String HOME_PAGE_KEY = "homePage";
  public static final String PAGE_DECORATORS_KEY = "pageDecorators";
  public static final String FEED_DECORATORS_KEY = "feedDecorators";
  public static final String OPEN_ID_COMMENT_AUTHOR_PROVIDERS_KEY = "openIdCommentAuthorProviders";
  public static final String XSRF_SIGNING_SALT_KEY = "signingSalt";

  /** the ID of this blog */
  private String id = "default";

  /** the root category associated with this blog */
  private Category rootCategory;

  /** the referer filter associated with this blog */
  private RefererFilterManager refererFilterManager;

  /** the editable theme belonging to this blog */
  private Theme editableTheme;

  /** the permalink provider in use */
  private PermalinkProvider permalinkProvider;

  /** the default permalink provider, for fallback */
  private PermalinkProvider defaultPermalinkProvider;

  /** the log used to log referers, requests, etc */
  private AbstractLogger logger;

  /** the decorator chain associated with this blog */
  private ContentDecoratorChain decoratorChain;

  private CommentConfirmationStrategy commentConfirmationStrategy;
  private TrackBackConfirmationStrategy trackBackConfirmationStrategy;

  /** the event dispatcher */
  private EventDispatcher eventDispatcher;

  /** the event listener list */
  private EventListenerList eventListenerList;

  /** the plugin properties */
  private PluginProperties pluginProperties;

  /** the blog companion */
  private BlogCompanion blogCompanion;

  private SearchIndex searchIndex;
  private BlogEntryIndex blogEntryIndex;
  private ResponseIndex responseIndex;
  private TagIndex tagIndex;
  private CategoryIndex categoryIndex;
  private AuthorIndex authorIndex;
  private StaticPageIndex staticPageIndex;

  private final List<PageDecorator> pageDecorators = new CopyOnWriteArrayList<PageDecorator>();
  private final List<OpenIdCommentAuthorProvider> openIdCommentAuthorProviders = new CopyOnWriteArrayList<OpenIdCommentAuthorProvider>();
  private final List<FeedDecorator> feedDecorators = new CopyOnWriteArrayList<FeedDecorator>();

  private EmailSubscriptionList emailSubscriptionList;

  /** the ApplicationContext to instantiate plugins with */
  private final AutowireCapableBeanFactory beanFactory;

  /** the Cache that can be used by services to cache arbitrary config */
  private final ConcurrentMap<String, Supplier<?>> serviceCache = new ConcurrentHashMap<String, Supplier<?>>();

  private final BlogManager blogManager;
  private final DAOFactory daoFactory;
  private final BlogService blogService;
  /**
   * Creates a new Blog instance, based at the specified location.
   *
   * @param root    an absolute path pointing to the root directory of the blog
   */
  public Blog(BlogManager blogManager, DAOFactory daoFactory, BlogService blogService, String root) {
    super(root);
    this.blogManager = blogManager;
    this.daoFactory = daoFactory;
    this.blogService = blogService;
    beanFactory = PebbleContext.getInstance().getApplicationContext().getAutowireCapableBeanFactory();

    // probably Blog should be made a final class if init is called from here - 
    // see javadoc comment on AbstractBlog.init() for reasons
    init();
  }

  /**
   * Initialize this blog - prepare it for use.
   * Note: As this blog instance is passed to the various participants while
   * it is being initialized, this method is dependent on the correct order
   * of calls: Keep in mind that 'this' is only partly initialized until the
   * end of this method...
   */

  protected void init() {
    super.init();

    setPermalinkProvider(instantiate(DefaultPermalinkProvider.class));
    defaultPermalinkProvider = permalinkProvider;
    try {
      // Override with configured permalink provider
      Class<?> c = Class.forName(getPermalinkProviderName());
      setPermalinkProvider(instantiate(c.asSubclass(PermalinkProvider.class)));
    } catch (Exception e) {
      error("Could not load permalink provider \"" + getPermalinkProviderName() + "\"");
      e.printStackTrace();
    }

    pluginProperties = new PluginProperties(this);
    blogCompanion = new BlogCompanion(this);

    // create the various indexes for this blog
    searchIndex = new LuceneSearchIndex(this);
    blogEntryIndex = daoFactory.getBlogEntryIndex();
    responseIndex = new FileResponseIndex(this);
    tagIndex = daoFactory.createTagIndex(this);
    categoryIndex = new FileCategoryIndex(this);
    authorIndex = daoFactory.createAuthorIndex(this);
    staticPageIndex = new FileStaticPageIndex(this);

    decoratorChain = new ContentDecoratorChain(this);

    daoFactory.init(this);

    refererFilterManager = new RefererFilterManager(this, daoFactory.getRefererFilterDAO());

    // load categories
    try {
      CategoryDAO dao = daoFactory.getCategoryDAO();
      rootCategory = dao.getCategories(this);
    } catch (PersistenceException pe) {
      pe.printStackTrace();
    }


    try {
      Class<?> c = Class.forName(getCommentConfirmationStrategyName());
      commentConfirmationStrategy = instantiate(c.asSubclass(CommentConfirmationStrategy.class));
    } catch (Exception e) {
      error("Could not load comment confirmation strategy \"" + getCommentConfirmationStrategyName() + "\"");
      e.printStackTrace();
      commentConfirmationStrategy = new DefaultConfirmationStrategy();
    }

    try {
      Class<?> c = Class.forName(getTrackBackConfirmationStrategyName());
      trackBackConfirmationStrategy = instantiate(c.asSubclass(TrackBackConfirmationStrategy.class));
    } catch (Exception e) {
      error("Could not load TrackBack confirmation strategy \"" + getTrackBackConfirmationStrategyName() + "\"");
      e.printStackTrace();
      trackBackConfirmationStrategy = new DefaultConfirmationStrategy();
    }

    emailSubscriptionList = new FileEmailSubscriptionList(this);

    initLogger();
    initEventDispatcher();
    initBlogListeners();
    initBlogEntryListeners();
    initCommentListeners();
    initTrackBackListeners();
    initDecorators();
    initPluginList(getPageDecoratorNames(), PageDecorator.class, getPageDecorators(), "Page Decorator");
    initPluginList(getOpenIdCommentAuthorProviderNames(), OpenIdCommentAuthorProvider.class,
        getOpenIdCommentAuthorProviders(), "OpenID Comment Author Provider");
    initPluginList(getFeedDecoratorNames(), FeedDecorator.class, getFeedDecorators(), "Feed Decorator");
  }

  /**
   * Initialises the logger for this blog.
   */
  private void initLogger() {
    log.debug("Initializing logger");

    try {
      Class c = Class.forName(getLoggerName());
      Constructor cons = c.getConstructor(Blog.class);
      this.logger = (AbstractLogger)cons.newInstance(this);
    } catch (Exception e) {
      error("Could not start logger \"" + getLoggerName() + "\"");
      e.printStackTrace();
      this.logger = new CombinedLogFormatLogger(this);
    }
  }

  /**
   * Initialises the event dispatcher for this blog.
   */
  private void initEventDispatcher() {
    log.debug("Initializing event dispatcher");
    eventListenerList = new EventListenerList();

    try {
      Class<?> c = Class.forName(getEventDispatcherName());
      this.eventDispatcher = instantiate(c.asSubclass(EventDispatcher.class));
    } catch (Exception e) {
      e.printStackTrace();
      this.eventDispatcher = new DefaultEventDispatcher();
    }

    eventDispatcher.setEventListenerList(eventListenerList);
  }

  /**
   * Initialises any blog listeners configured for this blog.
   */
  private void initBlogListeners() {
    log.debug("Registering blog listeners");

    for (String className : getBlogListeners()) {
      try {
        Class<?> c = Class.forName(className.trim());
        BlogListener listener = instantiate(c.asSubclass(BlogListener.class));
        eventListenerList.addBlogListener(listener);
      } catch (Exception e) {
        error("Could not start blog listener \"" + className + "\" - check the class name is correct on the <a href=\"viewPlugins.secureaction#blogListeners\">plugins page</a>.");
        log.error("Blog listener " + className + " could not be registered", e);
      }
    }
  }

  /**
   * Initialises any blog entry listeners configured for this blog.
   */
  private void initBlogEntryListeners() {
    log.debug("Registering blog entry listeners");

    for (String className : getBlogEntryListeners()) {
      try {
        Class<?> c = Class.forName(className.trim());
        BlogEntryListener listener = instantiate(c.asSubclass(BlogEntryListener.class));
        eventListenerList.addBlogEntryListener(listener);
      } catch (Exception e) {
        error("Could not start blog entry listener \"" + className + "\" - check the class name is correct on the <a href=\"viewPlugins.secureaction#blogEntryListeners\">plugins page</a>.");
        log.error("Blog entry listener " + className + " could not be registered", e);
      }
    }

    // these are required to keep the various indexes up to date
    eventListenerList.addBlogEntryListener(new BlogEntryIndexListener());
    eventListenerList.addBlogEntryListener(new TagIndexListener());
    eventListenerList.addBlogEntryListener(new CategoryIndexListener());
    eventListenerList.addBlogEntryListener(new AuthorIndexListener());
    eventListenerList.addBlogEntryListener(new SearchIndexListener());
    eventListenerList.addBlogEntryListener(new AuditListener());
    try {
      eventListenerList.addBlogEntryListener(new EmailSubscriptionListener());
    } catch (Throwable t) {
      final String text = "Error while starting e-mail subscription listener - add mail.jar and activation.jar to the server classpath if you want to enable this listener.";
      warn(text);
      if(t instanceof NoClassDefFoundError &&
         t.getMessage() != null &&
         t.getMessage().indexOf("javax/mail/Session") > -1) {
        log.warn(text); // consider exception already handled well...
      } else {
        log.warn(text, t);
      }
    }
  }

  /**
   * Initialises any comment listeners configured for this blog.
   */
  private void initCommentListeners() {
    log.debug("Registering comment listeners");

    for (String className : getCommentListeners()) {
      try {
        Class<?> c = Class.forName(className.trim());
        CommentListener listener = instantiate(c.asSubclass(CommentListener.class));
        eventListenerList.addCommentListener(listener);
      } catch (Exception e) {
        error("Could not start comment listener \"" + className + "\" - check the class name is correct on the <a href=\"viewPlugins.secureaction#commentListeners\">plugins page</a>.");
        log.error("Comment listener " + className + " could not be registered", e);
      }
    }

    eventListenerList.addCommentListener(new ResponseIndexListener());
    eventListenerList.addCommentListener(new AuditListener());
  }

  /**
   * Initialises any TrackBack listeners configured for this blog.
   */
  private void initTrackBackListeners() {
    log.debug("Registering TrackBack listeners");

    for (String className : getTrackBackListeners()) {
      try {
        Class<?> c = Class.forName(className.trim());
        TrackBackListener listener = instantiate(c.asSubclass(TrackBackListener.class));
        eventListenerList.addTrackBackListener(listener);
      } catch (Exception e) {
        error("Could not start TrackBack listener \"" + className + "\" - check the class name is correct on the <a href=\"viewPlugins.secureaction#trackbackListeners\">plugins page</a>.");
        log.error("TrackBack listener " + className + " could not be registered", e);
      }
    }

    eventListenerList.addTrackBackListener(new ResponseIndexListener());
    eventListenerList.addTrackBackListener(new AuditListener());
  }

  /**
   * Initialises any content decorators configufred for this blog.
   */
  private void initDecorators() {
    log.debug("Registering decorators");

    decoratorChain.add(new HideUnapprovedResponsesDecorator());

    for (String className : getContentDecorators()) {
      try {
        Class<?> c = Class.forName(className.trim());
        ContentDecorator decorator = instantiate(c.asSubclass(ContentDecorator.class));
        decorator.setBlog(this);
        decoratorChain.add(decorator);
      } catch (Exception e) {
        error("Could not start decorator \"" + className + "\" - check the class name is correct on the <a href=\"viewPlugins.secureaction#contentDecorators\">plugins page</a>.");
        e.printStackTrace();
        log.error(className + " could not be started", e);
      }
    }
  }

  /**
   * Initialises the list of string plugins into the specified list of plugin instances
   *
   * @param pluginNameList  The list of plugin class names
   * @param pluginClass     The type of the plugin
   * @param pluginList      The list of plugins to put the instantiated plugins into
   * @param description   A description of the plugin point for logging
   */
  @SuppressWarnings("unchecked")
  private <P> void initPluginList(Collection<String> pluginNameList, Class<P> pluginClass, List<P> pluginList, String description) {
    log.debug("Registering " + description + "s");

    for (String className : pluginNameList) {
      try {
        Class c = Class.forName(className.trim());
        Class<? extends P> concreteClass = c.asSubclass(pluginClass);
        P plugin = instantiate(concreteClass);
        pluginList.add(plugin);
      } catch (Exception e) {
        error("Could not start " + description + " \"" + className + "\".");
        log.error(description + " " + className + " could not be registered", e);
      }
    }
  }

  /**
   * Gets the default properties for a Blog.
   *
   * @return    a Properties instance
   */
  protected Properties getDefaultProperties() {
    Properties defaultProperties = new Properties();
    defaultProperties.setProperty(NAME_KEY, "My blog");
    defaultProperties.setProperty(DESCRIPTION_KEY, "");
    defaultProperties.setProperty(IMAGE_KEY, "");
    defaultProperties.setProperty(AUTHOR_KEY, "Blog Owner");
    defaultProperties.setProperty(EMAIL_KEY, "blog@yourdomain.com");
    defaultProperties.setProperty(TIMEZONE_KEY, "Europe/London");
    defaultProperties.setProperty(LANGUAGE_KEY, "en");
    defaultProperties.setProperty(COUNTRY_KEY, "GB");
    defaultProperties.setProperty(CHARACTER_ENCODING_KEY, "UTF-8");
    defaultProperties.setProperty(RECENT_BLOG_ENTRIES_ON_HOME_PAGE_KEY, "3");
    defaultProperties.setProperty(RECENT_RESPONSES_ON_HOME_PAGE_KEY, "3");
    defaultProperties.setProperty(THEME_KEY, "default");
    defaultProperties.setProperty(PRIVATE_KEY, FALSE);
    defaultProperties.setProperty(LUCENE_ANALYZER_KEY, "org.apache.lucene.analysis.SimpleAnalyzer");
    defaultProperties.setProperty(CONTENT_DECORATORS_KEY,
        "net.sourceforge.pebble.decorator.RadeoxDecorator\n" +
        "net.sourceforge.pebble.decorator.HtmlDecorator\n" +
        "net.sourceforge.pebble.decorator.EscapeMarkupDecorator\n" +
        "net.sourceforge.pebble.decorator.RelativeUriDecorator\n" +
        "net.sourceforge.pebble.decorator.ReadMoreDecorator\n" +
        "net.sourceforge.pebble.decorator.BlogTagsDecorator");
    defaultProperties.setProperty(BLOG_ENTRY_LISTENERS_KEY,
        "net.sourceforge.pebble.event.blogentry.XmlRpcNotificationListener");
    defaultProperties.setProperty(COMMENT_LISTENERS_KEY,
        "net.sourceforge.pebble.event.response.IpAddressListener\r\n" +
        "net.sourceforge.pebble.event.response.LinkSpamListener\r\n" +
        "net.sourceforge.pebble.event.response.ContentSpamListener\r\n" +
        "net.sourceforge.pebble.event.response.SpamScoreListener\r\n" +
        "net.sourceforge.pebble.event.response.MarkApprovedWhenAuthenticatedListener\r\n" +
        "#net.sourceforge.pebble.event.response.DeleteRejectedListener\r\n" +
        "#net.sourceforge.pebble.event.comment.EmailAuthorNotificationListener");
    defaultProperties.setProperty(TRACKBACK_LISTENERS_KEY,
        "net.sourceforge.pebble.event.response.IpAddressListener\r\n" +
        "net.sourceforge.pebble.event.response.LinkSpamListener\r\n" +
        "net.sourceforge.pebble.event.response.ContentSpamListener\r\n" +
        "net.sourceforge.pebble.event.response.SpamScoreListener\r\n" +
        "net.sourceforge.pebble.event.response.MarkApprovedWhenAuthenticatedListener\r\n" +
        "#net.sourceforge.pebble.event.response.DeleteRejectedListener\r\n" +
        "#net.sourceforge.pebble.event.trackback.EmailAuthorNotificationListener");
    defaultProperties.setProperty(PERMALINK_PROVIDER_KEY, "net.sourceforge.pebble.permalink.DefaultPermalinkProvider");
    defaultProperties.setProperty(EVENT_DISPATCHER_KEY, "net.sourceforge.pebble.event.DefaultEventDispatcher");
    defaultProperties.setProperty(LOGGER_KEY, "net.sourceforge.pebble.logging.CombinedLogFormatLogger");
    defaultProperties.setProperty(COMMENT_CONFIRMATION_STRATEGY_KEY, "net.sourceforge.pebble.confirmation.DefaultConfirmationStrategy");
    defaultProperties.setProperty(TRACKBACK_CONFIRMATION_STRATEGY_KEY, "net.sourceforge.pebble.confirmation.DefaultConfirmationStrategy");
    defaultProperties.setProperty(RICH_TEXT_EDITOR_FOR_COMMENTS_ENABLED_KEY, "true");
    defaultProperties.setProperty(GRAVATAR_SUPPORT_FOR_COMMENTS_ENABLED_KEY, "true");

    return defaultProperties;
  }

  /**
   * Gets the ID of this blog.
   *
   * @return  the ID as a String
   */
  public String getId() {
    return this.id;
  }

  /**
   * Sets the ID of this blog.
   *
   * @param id    the ID as a String
   */
  public void setId(String id) {
    this.id = id;
  }

  /**
   * Gets the URL where this blog is deployed.
   *
   * @return  a URL as a String
   */
  public String getUrl() {
    Configuration config = PebbleContext.getInstance().getConfiguration();
    String url = config.getUrl();

    if (url == null || url.length() == 0) {
      return "";
    } else if (blogManager.isMultiBlog()) {
      if (config.isVirtualHostingEnabled()) {
        return url.substring(0, url.indexOf("://")+3) + getId() + "." + url.substring(url.indexOf("://")+3);
      } else {
        return url + getId() + "/";
      }
    } else {
      return url;
    }
  }

  /**
   * Gets the relative URL where this blog is deployed.
   *
   * @return  a URL as a String
   */
  public String getRelativeUrl() {
    if (blogManager.isMultiBlog()) {
      return "/" + getId() + "/";
    } else {
      return "/";
    }
  }

  /**
   * Gets the about description of this blog.
   *
   * @return    a String
   */
  public String getAbout() {
    return properties.getProperty(ABOUT_KEY);
  }

  /**
   * Gets the home page to be used for this blog.
   *
   * @return    a String
   */
  public String getHomePage() {
    return properties.getProperty(HOME_PAGE_KEY);
  }

  /**
   * Gets the e-mail address of the blog owner.
   *
   * @return    the e-mail address
   */
  public String getEmail() {
    return properties.getProperty(EMAIL_KEY);
  }

  /**
   * Gets a Collection of e-mail addresses.
   *
   * @return  a Collection of String instances
   */
  public Collection getEmailAddresses() {
    return Arrays.asList(getEmail().split(","));
  }

  /**
   * Gets the first of multiple e-mail addresses.
   *
   * @return  the firt e-mail address as a String
   */
  public String getFirstEmailAddress() {
    Collection emailAddresses = getEmailAddresses();
    if (emailAddresses != null && !emailAddresses.isEmpty()) {
      return (String)emailAddresses.iterator().next();
    } else {
      return "";
    }
  }

  /**
   * Gets a comma separated list of the users that are blog owners
   * for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public String getBlogOwnersAsString() {
    return properties.getProperty(BLOG_OWNERS_KEY);
  }

  /**
   * Gets a list of the users that are blog owners for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public List<String> getBlogOwners() {
    String commaSeparatedUsers = getBlogOwnersAsString();
    List<String> users = new LinkedList<String>();
    if (commaSeparatedUsers != null) {
      StringTokenizer tok = new StringTokenizer(commaSeparatedUsers, ",");
      while (tok.hasMoreTokens()) {
        users.add(tok.nextToken().trim());
      }
    }

    return users;
  }

  /**
   * Gets a comma separated list of the users that are blog publishers
   * for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public String getBlogPublishersAsString() {
    return properties.getProperty(BLOG_PUBLISHERS_KEY);
  }

  /**
   * Gets a list of the users that are blog publishers for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public List<String> getBlogPublishers() {
    String commaSeparatedUsers = getBlogPublishersAsString();
    List<String> users = new LinkedList<String>();
    if (commaSeparatedUsers != null) {
      StringTokenizer tok = new StringTokenizer(commaSeparatedUsers, ",");
      while (tok.hasMoreTokens()) {
        users.add(tok.nextToken().trim());
      }
    }

    return users;
  }

  /**
   * Gets a comma separated list of the users that are blog contributors
   * for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public String getBlogContributorsAsString() {
    return properties.getProperty(BLOG_CONTRIBUTORS_KEY);
  }

  /**
   * Gets a list of the users that are blog contributors for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public List<String> getBlogContributors() {
    String commaSeparatedUsers = getBlogContributorsAsString();
    List<String> users = new LinkedList<String>();
    if (commaSeparatedUsers != null) {
      StringTokenizer tok = new StringTokenizer(commaSeparatedUsers, ",");
      while (tok.hasMoreTokens()) {
        users.add(tok.nextToken().trim());
      }
    }

    return users;
  }

  /**
   * Gets a comma separated list of the users that are blog readers
   * for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public String getBlogReadersAsString() {
    return properties.getProperty(BLOG_READERS_KEY);
  }

  /**
   * Gets a list of the users that are blog readers for this blog.
   *
   * @return  a String containng a comma separated list of user names
   */
  public List<String> getBlogReaders() {
    String commaSeparatedUsers = getBlogReadersAsString();
    List<String> users = new LinkedList<String>();
    if (commaSeparatedUsers != null) {
      StringTokenizer tok = new StringTokenizer(commaSeparatedUsers, ",");
      while (tok.hasMoreTokens()) {
        users.add(tok.nextToken().trim());
      }
    }

    return users;
  }

  /**
   * Gets the name of the Lucene analyzer to use.
   *
   * @return  a fully qualified class name
   */
  public String getLuceneAnalyzer() {
    return properties.getProperty(LUCENE_ANALYZER_KEY);
  }

  /**
   * Gets the name of the logger in use.
   *
   * @return  a fully qualified class name
   */
  public String getLoggerName() {
    return properties.getProperty(LOGGER_KEY);
  }

  /**
   * Gets a Collection containing the names of users that are blog owners
   * for this blog.
   *
   * @param roleName The role to get users for
   * @return  a Collection containng user names as Strings
   */
  public Collection<String> getUsersInRole(String roleName) {
    List<String> users = new LinkedList<String>();

    if (roleName.equals(Constants.BLOG_OWNER_ROLE)) {
      users = getBlogOwners();
    } else if (roleName.equals(Constants.BLOG_PUBLISHER_ROLE)) {
      users = getBlogPublishers();
    } else if (roleName.equals(Constants.BLOG_CONTRIBUTOR_ROLE)) {
      users = getBlogContributors();
    } else if (roleName.equals(Constants.BLOG_READER_ROLE)) {
      users = getBlogReaders();
    }

    return users;
  }

  /**
   * Determines whether the specified user is in the specified role.
   *
   * @param roleName    the name of the role
   * @param user        the name of the user
   * @return  true if the user is a member of the role or the list of users
   *          is empty, false otherwise
   */
  public boolean isUserInRole(String roleName, String user) {
    Collection users = getUsersInRole(roleName);
    return users.isEmpty() || users.contains(user);
  }

  /**
   * Gets the number of static pages for this blog.
   *
   * @return  an int
   */
  public int getNumberOfStaticPages() {
    return staticPageIndex.getNumberOfStaticPages();
  }

  /**
   * Gets the most recent blog entries, the number
   * of which is specified.
   *
   * @param numberOfEntries the number of entries to get
   * @return a List containing the most recent blog entries
   */
  public List<BlogEntry> getRecentBlogEntries(int numberOfEntries) {
    List<String> blogEntryIds = blogEntryIndex.getBlogEntries(this);
    List<BlogEntry> blogEntries = new ArrayList<BlogEntry>();
    for (String blogEntryId : blogEntryIds) {
      try {
        BlogEntry blogEntry = blogService.getBlogEntry(this, blogEntryId);
        blogEntries.add(blogEntry);
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }

      if (blogEntries.size() == numberOfEntries) {
        break;
      }
    }

    return blogEntries;
  }

  /**
   * Gets the most recent published blog entries, the number of which
   * is taken from the recentBlogEntriesOnHomePage property.
   *
   * @return a List containing the most recent blog entries
   */
  public List<BlogEntry> getRecentPublishedBlogEntries() {
    return getRecentPublishedBlogEntries(getRecentBlogEntriesOnHomePage());
  }

  /**
   * Gets the most recent published blog entries, the number of which
   * is specified
   *
   * @param number    the number of blog entries to get
   * @return a List containing the most recent blog entries
   */
  public List<BlogEntry> getRecentPublishedBlogEntries(int number) {
    List<String> blogEntryIds = blogEntryIndex.getPublishedBlogEntries(this);
    List<BlogEntry> blogEntries = new ArrayList<BlogEntry>();
    for (String blogEntryId : blogEntryIds) {
      if (blogEntries.size() == number) {
        break;
      }

      try {
        BlogEntry blogEntry = blogService.getBlogEntry(this, blogEntryId);
        if (blogEntry != null) {
          blogEntries.add(blogEntry);
        }
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }
    }

    return blogEntries;
  }

  /**
   * Gets blog entries for a given list of IDs.
   *
   * @param blogEntryIds    the list of blog entry IDs
   * @return a List containing the blog entries
   */
  public List<BlogEntry> getBlogEntries(List<String> blogEntryIds) {
    List<BlogEntry> blogEntries = new LinkedList<BlogEntry>();
    for (String blogEntryId : blogEntryIds) {
      try {
        BlogEntry blogEntry = blogService.getBlogEntry(this, blogEntryId);
        if (blogEntry != null) {
          blogEntries.add(blogEntry);
        }
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }
    }

    return blogEntries;
  }

  /**
   * Gets the most recent published blog entries for a given category, the
   * number of which is taken from the recentBlogEntriesOnHomePage property.
   *
   * @param   category          a category
   * @return  a List containing the most recent blog entries
   */
  public List<BlogEntry> getRecentPublishedBlogEntries(Category category) {
    List<String> blogEntryIds = categoryIndex.getRecentBlogEntries(category);
    List<BlogEntry> blogEntries = new ArrayList<BlogEntry>();
    for (String blogEntryId : blogEntryIds) {
      try {
        BlogEntry blogEntry = blogService.getBlogEntry(this, blogEntryId);
        if (blogEntry != null && blogEntry.isPublished()) {
          blogEntries.add(blogEntry);
        }
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }

      if (blogEntries.size() == getRecentBlogEntriesOnHomePage()) {
        break;
      }
    }

    return blogEntries;
  }

  /**
   * Gets the most recent published blog entries for a given category, the
   * number of which is taken from the recentBlogEntriesOnHomePage property.
   *
   * @param   author    the author's username
   * @return  a List containing the most recent blog entries
   */
  public List<BlogEntry> getRecentPublishedBlogEntries(String author) {
    List<String> blogEntryIds = authorIndex.getRecentBlogEntries(author);
    List<BlogEntry> blogEntries = new ArrayList<BlogEntry>();
    for (String blogEntryId : blogEntryIds) {
      try {
        BlogEntry blogEntry = blogService.getBlogEntry(this, blogEntryId);
        if (blogEntry != null && blogEntry.isPublished()) {
          blogEntries.add(blogEntry);
        }
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }

      if (blogEntries.size() == getRecentBlogEntriesOnHomePage()) {
        break;
      }
    }

    return blogEntries;
  }

  /**
   * Gets the most recent published blog entries for a given tag, the
   * number of which is taken from the recentBlogEntriesOnHomePage property.
   *
   * @param tag             a tag
   * @return a List containing the most recent blog entries
   */
  public List<BlogEntry> getRecentPublishedBlogEntries(Tag tag) {
    List<String> blogEntryIds = tagIndex.getRecentBlogEntries(tag);
    List<BlogEntry> blogEntries = new ArrayList<BlogEntry>();
    for (String blogEntryId : blogEntryIds) {
      try {
        BlogEntry blogEntry = blogService.getBlogEntry(this, blogEntryId);
        if (blogEntry != null && blogEntry.isPublished()) {
          blogEntries.add(blogEntry);
        }
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }

      if (blogEntries.size() == getRecentBlogEntriesOnHomePage()) {
        break;
      }
    }

    return blogEntries;
  }

  /**
   * Gets the most recent responses.
   *
   * @return a List containing the most recent blog entries
   */
  public List<Response> getRecentApprovedResponses() {
    List<String> responseIds = responseIndex.getApprovedResponses();
    List<Response> responses = new ArrayList<Response>();
    for (String responseId : responseIds) {
      try {
        Response response = blogService.getResponse(this, responseId);
        if (response != null && response.getBlogEntry().isPublished()) {
          responses.add(response);
        }
      } catch (BlogServiceException e) {
        log.error("Exception encountered", e);
      }

      if (responses.size() == getRecentResponsesOnHomePage()) {
        break;
      }
    }

    return responses;
  }

  /**
   * Gets the list of approved responses.
   *
   * @return  a List of response IDs
   */
  public List<String> getApprovedResponses() {
    return responseIndex.getApprovedResponses();
  }

  /**
   * Gets the list of pending responses.
   *
   * @return  a List of response IDs
   */
  public List<String> getPendingResponses() {
    return responseIndex.getPendingResponses();
  }

  /**
   * Gets the list of rejected responses.
   *
   * @return  a List of response IDs
   */
  public List<String> getRejectedResponses() {
    return responseIndex.getRejectedResponses();
  }

  /**
   * Gets the number of responses.
   *
   * @return the number of responses
   */
  public int getNumberOfResponses() {
    return responseIndex.getNumberOfResponses();
  }

  /**
   * Gets the number of approved responses.
   *
   * @return the number of approved responses
   */
  public int getNumberOfApprovedResponses() {
    return responseIndex.getNumberOfApprovedResponses();
  }

  /**
   * Gets the number of pending responses.
   *
   * @return the number of pending responses
   */
  public int getNumberOfPendingResponses() {
    return responseIndex.getNumberOfPendingResponses();
  }

  /**
   * Gets the number of rejected responses.
   *
   * @return the number of rejected responses
   */
  public int getNumberOfRejectedResponses() {
    return responseIndex.getNumberOfRejectedResponses();
  }

  /**
   * Gets the date that this blog was last updated through the addition
   * of a blog entry.
   *
   * @return  a Date instance representing the time of the most recent entry
   */
  public Date getLastModified() {
    Date date = new Date(0);
    List blogEntries = getRecentPublishedBlogEntries(1);
    if (blogEntries.size() == 1) {
      date = ((BlogEntry)blogEntries.get(0)).getDate();
    }

    return date;
  }

  /**
   * Gets the date of the most recent response.
   *
   * @return  a Date instance representing the time of the most recent entry
   */
  public Date getDateOfLastResponse() {
    List<Response> responses = this.getRecentApprovedResponses();
    if (responses != null && responses.size() > 0) {
      return responses.get(0).getDate();
    } else {
      return new Date(0);
    }
  }

  public BlogEntry getPreviousBlogEntry(BlogEntry blogEntry) {
    String blogEntryId = blogEntryIndex.getPreviousBlogEntry(this, blogEntry.getId());
    if (blogEntryId != null) {
      try {
        return blogService.getBlogEntry(this, blogEntryId);
      } catch (BlogServiceException e) {
        // do nothing
      }
    }
    return null;
  }

  public BlogEntry getNextBlogEntry(BlogEntry blogEntry) {
    String blogEntryId = blogEntryIndex.getNextBlogEntry(this, blogEntry.getId());
    if (blogEntryId != null) {
      try {
        return blogService.getBlogEntry(this, blogEntryId);
      } catch (BlogServiceException e) {
        // do nothing
      }
    }
    return null;
  }

  /**
   * Gets the categories associated with this blog.
   *
   * @return  a List of Category instances
   */
  public List<Category> getCategories() {
    CategoryBuilder builder = new CategoryBuilder(this, rootCategory);
    return builder.getCategories();
  }

  /**
   * Gets a specific category.
   *
   * @param id The id of the category
   * @return  a Category instance
   */
  public Category getCategory(String id) {
    CategoryBuilder builder = new CategoryBuilder(this, rootCategory);
    return builder.getCategory(id);
  }

  /**
   * Gets the root category for this blog.
   *
   * @return  a Category instance
   */
  public Category getRootCategory() {
    return this.rootCategory;
  }

  /**
   * Sets the root category for this blog.
   *
   * @param category    a Category instance
   */
  public void setRootCategory(Category category) {
    this.rootCategory = category;
  }

  /**
   * Adds a category.
   *
   * @param category    the Category to be added
   */
  public synchronized void addCategory(Category category) {
    if (getCategory(category.getId()) == null) {
      CategoryBuilder builder = new CategoryBuilder(this, rootCategory);
      builder.addCategory(category);
    }
  }

  /**
   * Removes a category.
   *
   * @param category    the Category to be removed
   */
  public synchronized void removeCategory(Category category) {
    if (getCategory(category.getId()) != null) {
      CategoryBuilder builder = new CategoryBuilder(this, rootCategory);
      builder.removeCategory(category);
    }
  }

  /**
   * Gets the list of tags associated with this blog.
   *
   * @return The list of tags
   */
  public Collection<Tag> getTags() {
    return tagIndex.getTags();
  }

  /**
   * Gets the tag with the specified name.
   *
   * @param name    the name as a String
   * @return  a Tag instance
   */
  public Tag getTag(String name) {
    return new Tag(Tag.encode(name), this);
  }

  /**
   * Gets the object managing referer filters.
   *
   * @return  a RefererFilterManager instance
   */
  public RefererFilterManager getRefererFilterManager() {
    return this.refererFilterManager;
  }

  /**
   * Gets the search index.
   *
   * @return  a BlogEntryIndex instance
   */
  public SearchIndex getSearchIndex() {
    return this.searchIndex;
  }

  /**
   * Gets the blog entry index.
   *
   * @return  a BlogEntryIndex instance
   */
  public BlogEntryIndex getBlogEntryIndex() {
    return this.blogEntryIndex;
  }

  /**
   * Gets the response index.
   *
   * @return  a ResponseIndex instance
   */
  public ResponseIndex getResponseIndex() {
    return this.responseIndex;
  }

  /**
   * Gets the tag index.
   *
   * @return  a TagIndex instance
   */
  public TagIndex getTagIndex() {
    return this.tagIndex;
  }

  /**
   * Gets the category index.
   *
   * @return  a CategoryIndex instance
   */
  public CategoryIndex getCategoryIndex() {
    return this.categoryIndex;
  }

  /**
   * Gets the author index.
   *
   * @return  a AuthorIndex instance
   */
  public AuthorIndex getAuthorIndex() {
    return this.authorIndex;
  }

  /**
   * Gets the story index.
   *
   * @return  a StaticPageIndex instance
   */
  public StaticPageIndex getStaticPageIndex() {
    return this.staticPageIndex;
  }

  /**
   * Logs this request for blog.
   *
   * @param request   the HttpServletRequest instance for this request
   */
  public synchronized void log(HttpServletRequest request, int status) {
    String externalUri = (String)request.getAttribute(Constants.EXTERNAL_URI);
    if (externalUri.matches("/images/.+")) {
      // do nothing, we don't want to log the following types of requests
      // - a blog's images
    } else {
      // log the request
      logger.log(request, status);
    }
  }

  /**
   * Gets an object representing the editable theme.
   *
   * @return    an EditableTheme instance
   */
  public Theme getEditableTheme() {
    return editableTheme;
  }

  /**
   * Sets an object representing the editable theme.
   *
   * @param editableTheme    an EditableTheme instance
   */
  public void setEditableTheme(Theme editableTheme) {
    this.editableTheme = editableTheme;
  }

  /**
   * Gets the location where the blog files are stored.
   *
   * @return    an absolute, local path on the filing system
   */
  public String getFilesDirectory() {
    return getRoot() + File.separator + "files";
  }

  /**
   * Gets the location where the blog theme is stored.
   *
   * @return    an absolute, local path on the filing system
   */
  public String getThemeDirectory() {
    return getRoot() + File.separator + "theme";
  }

  /**
   * Gets the location where the plugin properties file is stored.
   *
   * @return    an absolute, local path on the filing system
   */
  public String getPluginPropertiesFile() {
    return getRoot() + File.separator + "plugin.properties";
  }

  /**
   * Gets the location where the plugin properties file is stored.
   *
   * @return    an absolute, local path on the filing system
   */
  public String getCompanionFile() {
    return getRoot() + File.separator + "companion.txt";
  }

  /**
   * Determines whether this blog is public.
   *
   * @return  true if public, false otherwise
   */
  public boolean isPublic() {
    return properties.getProperty(PRIVATE_KEY).equalsIgnoreCase(FALSE);
  }

  /**
   * Determines whether this blog is private.
   *
   * @return  true if public, false otherwise
   */
  public boolean isPrivate() {
    return properties.getProperty(PRIVATE_KEY).equalsIgnoreCase(TRUE);
  }

  /**
   * Called to start (i.e. activate/initialise, restore the theme, etc) this
   * blog.
   */
  void start() {
    log.debug("Starting blog with ID " + getId());

    // reindex the blog if the indexes don't exist
    File indexes = new File(getIndexesDirectory());
    if (!indexes.exists()) {
      indexes.mkdir();
      reindex();
    }

    File imagesDirectory = new File(getImagesDirectory());
    if (!imagesDirectory.exists()) {
      imagesDirectory.mkdir();
    }

    File filesDirectory = new File(getFilesDirectory());
    if (!filesDirectory.exists()) {
      filesDirectory.mkdir();
    }

    File logDirectory = new File(getLogsDirectory());
    if (!logDirectory.exists()) {
      logDirectory.mkdir();
    }

    logger.start();
    editableTheme.restore();

    // call blog listeners
    eventDispatcher.fireBlogEvent(new BlogEvent(this, BlogEvent.BLOG_STARTED));
    log.info("Started blog with ID " + getId());
  }

  /**
   * Called to shutdown this blog.
   */
  void stop() {
    log.debug("Stopping blog with ID " + getId());

    logger.stop();
    editableTheme.backup();

    // call blog listeners
    eventDispatcher.fireBlogEvent(new BlogEvent(this, BlogEvent.BLOG_STOPPED));
    log.info("Stopped blog with ID " + getId());
  }

  /**
   * Gets the logger associated with this blog.
   *
   * @return    an AbstractLogger implementation
   */
  public AbstractLogger getLogger() {
    return this.logger;
  }

  /**
   * Gets the list of plugins.
   *
   * @return  a comma separated list of class names
   */
  public List<String> getContentDecorators() {
    return getStringsFromProperty(CONTENT_DECORATORS_KEY);
  }

  /**
   * Gets the decorator manager associated with this blog.
   *
   * @return  a BlogEntryDecoratorManager instance
   */
  public ContentDecoratorChain getContentDecoratorChain() {
    return this.decoratorChain;
  }

  /**
   * Gets the list of blog listeners as strings.
   *
   * @return  The list of class names
   */
  public List<String> getBlogListeners() {
    return getStringsFromProperty(BLOG_LISTENERS_KEY);
  }

  /**
   * Gets the list of blog entry listeners as strings.
   *
   * @return  The list of class names
   */
  public List<String> getBlogEntryListeners() {
    return getStringsFromProperty(BLOG_ENTRY_LISTENERS_KEY);
  }

  /**
   * Gets the list of comment listeners as strings.
   *
   * @return  The list of class names
   */
  public List<String> getCommentListeners() {
    return getStringsFromProperty(COMMENT_LISTENERS_KEY);
  }

  /**
   * Gets the list of TrackBack listeners as strings.
   *
   * @return  The list of class names
   */
  public List<String> getTrackBackListeners() {
    return getStringsFromProperty(TRACKBACK_LISTENERS_KEY);
  }

  /**
   * Gets the list of page decorators as Strings
   *
   * @return  The list of class names
   */
  public List<String> getPageDecoratorNames() {
    return getStringsFromProperty(PAGE_DECORATORS_KEY);
  }

  /**
   * Gets the list of feed decorators as Strings
   *
   * @return  The list of class names
   */
  public List<String> getFeedDecoratorNames() {
    return getStringsFromProperty(FEED_DECORATORS_KEY);
  }

  /**
   * Gets the list of OpenID comment author providers as Strings
   *
   * @return  The list of class names
   */
  public List<String> getOpenIdCommentAuthorProviderNames() {
    return getStringsFromProperty(OPEN_ID_COMMENT_AUTHOR_PROVIDERS_KEY);
  }

  /**
   * Gets the name the event dispatcher.
   *
   * @return  a String
   */
  public String getEventDispatcherName() {
    return getProperty(EVENT_DISPATCHER_KEY);
  }

  /**
   * Gets the event dispatcher in use.
   *
   * @return  an EventDispatcher implementation
   */
  public EventDispatcher getEventDispatcher() {
    return this.eventDispatcher;
  }

  /**
   * Gets the event listsner list.
   *
   * @return The event listener list
   */
  public EventListenerList getEventListenerList() {
    return this.eventListenerList;
  }

  public PluginProperties getPluginProperties() {
    return this.pluginProperties;
  }
  public BlogCompanion getBlogCompanion() {
    return this.blogCompanion;
  }

  /**
   * Gets the name of the permalink provider.
   *
   * @return    the fully qualified class name of the permalink provider
   */
  public String getPermalinkProviderName() {
    return properties.getProperty(PERMALINK_PROVIDER_KEY);
  }

  /**
   * Gets the permalink provider in use.
   *
   * @return  a PermalinkProvider instance
   */
  public PermalinkProvider getPermalinkProvider() {
    return this.permalinkProvider;
  }

  /**
   * Gets the default permalink provider to fallback to in case the permalink provider fails.
   *
   * @return  a PermalinkProvider instance
   */
  public PermalinkProvider getDefaultPermalinkProvider() {
    return this.defaultPermalinkProvider;
  }

  /**
   * Sets the permalink provider in use.
   *
   * @param provider    PermalinkProvider instance
   */
  public void setPermalinkProvider(PermalinkProvider provider) {
    this.permalinkProvider = provider;
    this.permalinkProvider.setBlog(this);
  }

  public List<PageDecorator> getPageDecorators() {
    return pageDecorators;
  }

  public List<OpenIdCommentAuthorProvider> getOpenIdCommentAuthorProviders() {
    return openIdCommentAuthorProviders;
  }

  public List<FeedDecorator> getFeedDecorators() {
    return feedDecorators;
  }

  public void reindex() {
    log.info("Reindexing blog with ID " + getId());

    reindexBlogEntries();
    reindexStaticPages();
  }

  public void reindexBlogEntries() {
    blogEntryIndex.clear(this);
    responseIndex.clear();
    tagIndex.clear();
    categoryIndex.clear();
    authorIndex.clear();
    searchIndex.clear();

    try {
      // to reindex all blog entries, we need to load them via the DAO
      Collection<BlogEntry> blogEntries = daoFactory.getBlogEntryDAO().loadBlogEntries(this);
      blogEntryIndex.index(this, blogEntries);
      responseIndex.index(blogEntries);
      tagIndex.index(blogEntries);
      categoryIndex.index(blogEntries);
      authorIndex.index(blogEntries);
      searchIndex.indexBlogEntries(blogEntries);
      info("Blog entries reindexed.");
    } catch (Exception e) {
      error(e.getClass().getName() + " reindexing blog entries - " + StringUtils.transformHTML(e.getMessage()));
      log.error("Error reindexing blog entries", e);
    }
  }

  public void reindexStaticPages() {
    try {
      // to reindex all static pages, we need to load them via the DAO
      Collection<StaticPage> staticPages = daoFactory.getStaticPageDAO().loadStaticPages(this);
      staticPageIndex.reindex(staticPages);
      searchIndex.indexStaticPages(staticPages);
      info("Static pages reindexed.");
    } catch (Exception e) {
      error(e.getClass().getName() + " reindexing static pages - " + StringUtils.transformHTML(e.getMessage()));
      log.error("Error reindexing static pages", e);
    }
  }

  /**
   * Indicates whether some other object is "equal to" this one.
   *
   * @param o   the reference object with which to compare.
   * @return <code>true</code> if this object is the same as the obj
   *         argument; <code>false</code> otherwise.
   * @see #hashCode()
   * @see java.util.Hashtable
   */
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof Blog)) {
      return false;
    }

    Blog blog = (Blog)o;
    return getId().equals(blog.getId());
  }


  public String getCommentConfirmationStrategyName() {
    return getProperty(COMMENT_CONFIRMATION_STRATEGY_KEY);
  }

  public CommentConfirmationStrategy getCommentConfirmationStrategy() {
    return commentConfirmationStrategy;
  }

  public String getTrackBackConfirmationStrategyName() {
    return getProperty(TRACKBACK_CONFIRMATION_STRATEGY_KEY);
  }

  public TrackBackConfirmationStrategy getTrackBackConfirmationStrategy() {
    return trackBackConfirmationStrategy;
  }

  public boolean isRichTextEditorForCommentsEnabled() {
    return Boolean.parseBoolean(getProperty(RICH_TEXT_EDITOR_FOR_COMMENTS_ENABLED_KEY));
  }

  public boolean isGravatarSupportForCommentsEnabled() {
    return Boolean.parseBoolean(getProperty(GRAVATAR_SUPPORT_FOR_COMMENTS_ENABLED_KEY));
  }

  public EmailSubscriptionList getEmailSubscriptionList() {
    return emailSubscriptionList;
  }

  public List<NewsFeedEntry> getNewsFeedEntries() {
    return NewsFeedCache.getInstance().getNewsFeedEntries(this);
  }

  public List<NewsFeedEntry> getRecentNewsFeedEntries() {
    List<NewsFeedEntry> entries = getNewsFeedEntries();
    if (entries.size() > getRecentBlogEntriesOnHomePage()) {
      entries = entries.subList(0, getRecentBlogEntriesOnHomePage());
    }
    return entries;
  }

  public String getXsrfSigningSalt() {
    String salt = getProperty(XSRF_SIGNING_SALT_KEY);
    if (salt == null) {
      // Generate salt
      byte[] saltBytes = new byte[8];
      new SecureRandom().nextBytes(saltBytes);
      salt = new String(Hex.encodeHex(saltBytes));
      setProperty(XSRF_SIGNING_SALT_KEY, salt);
      try {
        storeProperties();
      } catch (BlogServiceException e) {
        log.error("Error saving XSRF salt", e);
      }
    }
    return salt;
  }

  /**
   * Get an item from the cache
   *
   * @param key The key in the cache
   * @param supplier The supplier of the item.  This would usually be a memoized supplier, created using Suppliers.memoize()
   * @param <T> The type of the time
   *
   * @return The item
   */
  public <T> T getServiceCacheItem(String key, Supplier<T> supplier) {
    Supplier<T> memoizedSupplier = Suppliers.memoize(supplier);
    Supplier<T> cachedSupplier = (Supplier<T>) serviceCache.putIfAbsent(key, memoizedSupplier);
    if (cachedSupplier == null) {
      return memoizedSupplier.get();
    } else {
      return cachedSupplier.get();
    }
  }

  /**
   * Reset an item in the cache
   *
   * @param key The item to reset
   */
  public void resetServiceCacheItem(String key) {
    serviceCache.remove(key);
  }

  private List<String> getStringsFromProperty(String key) {
    List<String> strings = new ArrayList<String>();
    String value = getProperty(key);
    if (value != null && value.length() > 0) {
      String values[] = value.split("\\s+");
      for (String val :  values) {
        if (!val.startsWith("#")) {
          strings.add(val.trim());
        }
      }
    }
    return strings;
  }

  private <T> T instantiate(Class<T> clazz) {
    return (T) beanFactory.autowire(clazz, AutowireCapableBeanFactory.AUTOWIRE_NO, false);
  }
}
