import {
  AppWindow, ArrowDown, ArrowLeft, ArrowRight, BarChart3, Bell, Bot, Boxes,
  Check, CheckCircle, CheckSquare, ChevronLeft, ChevronRight, CircleAlert, CircleX,
  Cloud, CloudCog, Compass, Copy, Database, Download, Ellipsis, Eye, FileText,
  Filter, FlaskConical, FolderOpen, FunctionSquare, Gauge, GitBranch, GitFork,
  History, IdCard, LayoutDashboard, Lightbulb, Link, List, Maximize, MessageCircle, Minimize, Monitor,
  PanelLeftClose, PanelLeftOpen, Pencil, PlayCircle, Plus, Printer, Redo, RefreshCw,
  Rocket, Save, Search, Send, Settings, ShieldCheck, Sparkles, Star, Table2,
  Target, Trash2, Undo, User, Wrench, Zap,
} from 'lucide-react';

export const AimOutlined = Target;
export const ApartmentOutlined = GitFork;
export const AppstoreOutlined = AppWindow;
export const ArrowLeftOutlined = ArrowLeft;
export const ArrowRightOutlined = ArrowRight;
export const BarChartOutlined = BarChart3;
export const BellOutlined = Bell;
export const BranchesOutlined = GitBranch;
export const BulbOutlined = Lightbulb;
export const CheckCircleFilled = CheckCircle;
export const CheckCircleOutlined = CheckCircle;
export const CheckOutlined = Check;
export const CheckSquareOutlined = CheckSquare;
export const ClockCircleOutlined = Gauge;
export const CloseCircleOutlined = CircleX;
export const CloseOutlined = CircleX;
export const CloudServerOutlined = Cloud;
export const CloudSyncOutlined = ({ spin, ...props }: any) => <CloudCog {...props} className={spin ? 'icon-spin' : props.className} />;
export const CommentOutlined = MessageCircle;
export const CompressOutlined = Minimize;
export const CompassOutlined = Compass;
export const ControlOutlined = Wrench;
export const CopyOutlined = Copy;
export const DashboardOutlined = LayoutDashboard;
export const DatabaseOutlined = Database;
export const DeleteOutlined = Trash2;
export const DeploymentUnitOutlined = Boxes;
export const DownOutlined = ArrowDown;
export const DownloadOutlined = Download;
export const EditOutlined = Pencil;
export const EllipsisOutlined = Ellipsis;
export const ExpandOutlined = Maximize;
export const ExperimentOutlined = FlaskConical;
export const EyeOutlined = Eye;
export const FieldStringOutlined = FileText;
export const FilterOutlined = Filter;
export const FolderOpenOutlined = FolderOpen;
export const FunctionOutlined = FunctionSquare;
export const FundOutlined = BarChart3;
export const HistoryOutlined = History;
export const IdcardOutlined = IdCard;
export const LeftOutlined = ChevronLeft;
export const LinkOutlined = Link;
export const MenuFoldOutlined = PanelLeftClose;
export const MenuUnfoldOutlined = PanelLeftOpen;
export const MessageOutlined = MessageCircle;
export const MonitorOutlined = Monitor;
export const NotificationOutlined = Bell;
export const PartitionOutlined = GitFork;
export const PlayCircleOutlined = PlayCircle;
export const PlusOutlined = Plus;
export const PrinterOutlined = Printer;
export const RedoOutlined = Redo;
export const ReloadOutlined = ({ spin, ...props }: any) => <RefreshCw {...props} className={spin ? 'icon-spin' : props.className} />;
export const RobotOutlined = Bot;
export const RocketOutlined = Rocket;
export const SafetyCertificateOutlined = ShieldCheck;
export const SaveOutlined = Save;
export const SearchOutlined = Search;
export const SendOutlined = Send;
export const SettingOutlined = Settings;
export const StarOutlined = Star;
export const TableOutlined = Table2;
export const ThunderboltOutlined = Zap;
export const ToolOutlined = Wrench;
export const UndoOutlined = Undo;
export const UnorderedListOutlined = List;
export const UserOutlined = User;
export const WarningOutlined = CircleAlert;
