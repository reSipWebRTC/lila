package lila.forum

import scala.concurrent.duration._

import lila.user.User
import lila.memo.CacheApi._

final class Recent(
    postApi: PostApi,
    postRepo: PostRepo,
    cacheApi: lila.memo.CacheApi,
    categIds: List[String]
)(implicit ec: scala.concurrent.ExecutionContext) {

  private val nb: Int = 12

  private type GetTeamIds = String => Fu[List[String]]

  def apply(user: Option[User], getTeams: GetTeamIds): Fu[List[MiniForumPost]] =
    userCacheKey(user, getTeams) flatMap cache.get

  def team(teamId: String): Fu[List[MiniForumPost]] = {
    // prepend empty language list
    val key = ";" + teamSlug(teamId)
    cache get key
  }

  def invalidate(): Unit = cache.invalidateAll

  private def userCacheKey(user: Option[User], getTeams: GetTeamIds): Fu[String] =
    (user.map(_.id) ?? getTeams).map { teamIds =>
      user.fold("en")(_.langs.mkString(",")) :: {
        (user.??(_.troll) ?? List("[troll]")) :::
          categIds ::: teamIds.view.map(teamSlug).toList
      } mkString ";"
    }

  private val cache = cacheApi[String, List[MiniForumPost]](2048, "forum.recent") {
    _.expireAfterAccess(1 hour)
      .buildAsyncFuture(fetch)
  }

  private def parseLangs(langStr: String) = langStr.split(",").toList filter (_.nonEmpty)

  private def fetch(key: String): Fu[List[MiniForumPost]] =
    (key.split(";").toList match {
      case langs :: "[troll]" :: categs =>
        postRepo.withTroll(true).recentInCategs(nb)(categs, parseLangs(langs))
      case langs :: categs => postRepo.recentInCategs(nb)(categs, parseLangs(langs))
      case categs          => postRepo.recentInCategs(nb)(categs, parseLangs("en"))
    }) flatMap postApi.miniPosts
}
