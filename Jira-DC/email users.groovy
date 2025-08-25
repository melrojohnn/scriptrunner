import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.security.login.LoginManager
import com.atlassian.mail.Email
import groovy.xml.MarkupBuilder
import org.jsoup.Jsoup

import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import java.text.DateFormat

def loginManager = ComponentAccessor.getComponent(LoginManager)
def groupManager = ComponentAccessor.groupManager
def userUtil = ComponentAccessor.userUtil

def adminGroup = groupManager.getUsersInGroup('jira-administrators')
def softwareGroup = groupManager.getUsersInGroup('jira-software-users')
def serviceDeskGroup = groupManager.getUsersInGroup('jira-servicedesk-users')

def users = adminGroup + softwareGroup + serviceDeskGroup
users.unique()

//Set the file path
final def filePath = '/var/atlassian/application-data/jira/data/exports'
//Set the filename
final def filename = 'unlicensed_users.csv'
//Set the email subject
final def subject = 'Usuário inativo'
//Set the email address
final def emailAddr = 'johnnes.melro@gmail.com'
//You need to set your preferred language, e.g. 'en' for English
final def language = 'English'
//You need to set the country for the language format, e.g. UK for UK English, US for US English
final def country = 'US English'
//You need to set your location, e.g. 'America/New_York'
final def location = 'Brasil/Brasilia'

def emailBody = new StringWriter()
def html = new MarkupBuilder(emailBody)
html.html {
    head {
        style (type:'text/css', """
            table {
              border-collapse: collapse;
              width: 100%;
            }
            th, td {
              text-align: left;
              padding: 8px;
            }
            tr:nth-child(even){background-color: #f2f2f2}
            th {
              background-color: #04AA6D;
              color: white;
            }
        """)
    }
    body  {
        table  {
            thead  {
                tr  {
                    th 'User Name'
                    th 'Full Name'
                    th 'Email Address'
                    th 'Last Login'
                    th 'Status'
                }
                users.each {
                    def lastLoginTime = loginManager.getLoginInfo(it.username).lastLoginTime
                    def username = it.username
                    def displayName = it.displayName
                    def emailAddress = it.emailAddress
                    def active = it.active
                    if (userUtil.getGroupsForUser(it.name).size() > 0) {
                        tr {
                            td ( username )
                            td ( displayName )
                            td ( emailAddress )
                            if (!active) {
                                td ( 'Inactive User' )
                            } else if (!lastLoginTime) {
                                td ('Logon not found' )
                            } else {
                                def now = Calendar.instance
                                def locale = new Locale(language, country)
                                def dateFormatter = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale)
                                def timeZone = TimeZone.getTimeZone(location)
                                dateFormatter.setTimeZone(timeZone)
                                def dateText = dateFormatter.format(now.time)
                                td ( dateText )
                            }
                            td ( active )
                        }
                    }
                }
            }
        }
    }
}
def dest = new File("${filePath}/${filename}.csv")
dest.createNewFile()

def fileWriter = new FileWriter("${filePath}/${filename}.csv")
fileWriter.write(generateCSV(emailBody.toString()))
fileWriter.close()

creatMessage(emailAddr, subject, emailBody.toString(), dest)
dest.delete()

//Generate CSV File
final static String generateCSV(String tableDetails) {
    def stringBuilder = new StringBuilder()
    def doc = Jsoup.parseBodyFragment(tableDetails)
    def rows = doc.getElementsByTag('tr')
    rows.each {
        def header = it.getElementsByTag('th')
        def cells = it.getElementsByTag('td')
        header.each { headerCell ->
            stringBuilder.append(headerCell.text().concat('; '))
        }
        cells.each { cell ->
            stringBuilder.append(cell.text().concat('; '))
        }
        stringBuilder.append('\n')
    }
    //Remove empty line in CSV
    def last = stringBuilder.lastIndexOf('\n')
    if (last > 0) {
        stringBuilder.delete(last, stringBuilder.length())
    }
    stringBuilder.toString()
}

final static creatMessage(String to, String subject, String content, File file) {
    def mailServerManager = ComponentAccessor.mailServerManager
    def mailServer = mailServerManager.defaultSMTPMailServer
    def multipart = new MimeMultipart()
    def body = new MimeBodyPart()
    def mailAttachment = new MimeBodyPart()

    body.setContent(content, 'text/html; charset=utf-8')
    mailAttachment.attachFile(file)

    multipart.addBodyPart(body)
    multipart.addBodyPart(mailAttachment)

    def email = new Email(to)
    email.setSubject(subject)
    email.setMultipart(multipart)
    email.setMimeType("text/html")

    def threadClassLoader = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = mailServer.class.classLoader
    mailServer.send(email)
    Thread.currentThread().contextClassLoader = threadClassLoader
}