<%@ taglib uri="http://java.sun.com/jstl/core_rt" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jstl/fmt_rt" prefix="fmt" %>
<%@ taglib uri="http://pebble.sourceforge.net/pebble" prefix="pebble" %>

<%--
  Displays the recent blog entries.

 Parameters
  - showBody : flag to indicate whether the (truncated) body of the entry should be displayed
--%>
<%@ attribute name="showBody"%>

<c:if test="${empty showBody}"><c:set var="showBody" value="true"/></c:if> 

<c:if test="${not empty recentBlogEntries}">
<div class="sidebarItem">
  <div class="sidebarItemTitle"><span><fmt:message key="sidebar.recentBlogEntries" /> <a href="rss.xml"><img src="common/images/feed-icon-10x10.png" alt="RSS feed" border="0" /></a></span></div>
  <div class="sidebarItemBody">
    <ul>
    <c:forEach var="recentBlogEntry" items="${recentBlogEntries}" >
      <li><a href="${recentBlogEntry.permalink}" title="${recentBlogEntry.permalink}">${recentBlogEntry.title}</a>
        <c:if test="${showBody eq 'true'}">
        <br />${recentBlogEntry.truncatedContent}
        </c:if>
      </li>
    </c:forEach>
    </ul>
  </div>
</div>
</c:if>