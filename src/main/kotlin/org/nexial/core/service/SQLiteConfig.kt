package org.nexial.core.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.sqlite.javax.SQLiteConnectionPoolDataSource

object SQLiteConfig {
    @Autowired
    lateinit var sqlCreateStatements: List<String>

    @Autowired
    lateinit var sqlInsertStatements: Map<String, String>

    @Autowired
    lateinit var dataSource: SQLiteConnectionPoolDataSource

    fun execute(sql: String, vararg params: Any): Int = JdbcTemplate(dataSource).update(sql, *params)

    fun execute(sql: String): Int = JdbcTemplate(dataSource).update(sql)
}